package org.maplibre.android.session

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.maplibre.android.log.Logger
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the signed v2 map-session credential: it decides what to sign, when to buy a new
 * window, and when a 401 is actually about the credential we hold.
 *
 * The whole point of the feature is ONE billed map load per window of use, so nearly every
 * gate in here is a billing gate rather than a correctness gate. The comments say which.
 *
 * All public entry points are safe to call from any thread; the shared state is guarded by
 * [lock], which is never held across a network call.
 */
object MMMapSession {

    private const val TAG = "Mbgl-MMMapSession"

    /** The path fragment that identifies a v2 tile request. */
    const val TILE_PATH_MARKER = "/v2/tiles/"

    /** The six short-form credential parameters carried on a signed tile URL. */
    private val CREDENTIAL_PARAMS = listOf("u", "s", "e", "a", "k", "sig")

    /**
     * Three, not one and not ten. A single hard failure is legitimately reachable during a
     * signing-key rotation, and giving up on it would blank a map that would have recovered
     * on the next try. Three consecutive failures SPACED BY [MIN_HARD_FAILURE_SPACING_SECONDS]
     * are not a wobble: the only thing that produces them is a credential the gateway will
     * never accept — a misconfigured or revoked API key, or a key without map-session scope.
     */
    const val MAX_CONSECUTIVE_HARD_FAILURES = 3

    /**
     * The budget above counts ATTEMPTS, not round trips. Every unsigned tile that 401s drives
     * another [refreshNow] immediately, so a hundred in-flight tiles could burn three failures
     * in well under a second and a two-second gateway wobble would blank the map for the life
     * of the process. Failures closer together than this are the same incident.
     */
    const val MIN_HARD_FAILURE_SPACING_SECONDS = 30L

    /**
     * Even a "permanent" failure may not be: a key can be re-provisioned or a bad gateway
     * deployment rolled back, neither of which the app sees. After this long we try once more.
     * [onEnterForeground] is the fast path; this is for a map left open.
     */
    const val GIVE_UP_COOLDOWN_SECONDS = 600L

    private val lock = ReentrantLock()

    // --- credential ------------------------------------------------------------------
    private var account: String? = null
    private var sessionId: String? = null
    private var sig: String? = null
    private var keyId: String? = null
    private var exp: Long = 0
    private var sae: Long = 0

    // --- gateway origin --------------------------------------------------------------
    private var origin: HttpUrl? = null
    private var originIsConfigured = false
    private var loggedOriginMismatch = false
    private var originMismatchLogs = 0

    // --- refresh state ---------------------------------------------------------------
    private var refreshInFlight = false
    private var activity = false
    private var hardFailures = 0
    private var gaveUp = false
    private var gaveUpAt = 0L
    private var lastCountedFailureAt = 0L
    private var refreshCalls = 0
    private var cachedApiKey: String? = null
    private var timer: ScheduledFuture<*>? = null

    /**
     * How long before `exp` to renew. Must be shorter than the gateway's session ttl.
     */
    @Volatile
    var renewLeadTimeSeconds: Long = 60

    /**
     * The call factory used for create/renew. Replaced in tests so no traffic leaves the JVM.
     */
    @JvmStatic
    var callFactory: Call.Factory = OkHttpClient()

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            // A dedicated daemon thread, NOT the main looper: renewal is a billing call and
            // must not queue behind UI work, and it must not keep the JVM alive either.
            Thread(runnable, "MMMapSession").apply { isDaemon = true }
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    // ---------------------------------------------------------------------------------
    // API key
    // ---------------------------------------------------------------------------------

    /**
     * Caches the API key, pushed in from `MapLibre.getInstance` and `MapLibre.setApiKey`.
     *
     * The key is PUSHED rather than pulled so that [refreshNow] — which runs on OkHttp dispatcher
     * threads — never reaches back into `MapLibre`'s static state. `MapLibre` is annotated
     * `@UiThread` and is documented to be configured from the UI thread, so reading it from a
     * network thread would be using it outside its contract. (`MapLibre.getApiKey()` happens to
     * be a plain field read today and does NOT throw off the main thread — but nothing keeps it
     * that way, and depending on that is what this avoids.)
     *
     * Both callers must be kept in step: a key rotated through `setApiKey` and not pushed here
     * leaves every create sending a dead token, which 401s until the give-up guard trips.
     */
    @JvmStatic
    fun cacheApiKey(apiKey: String?) = lock.withLock {
        cachedApiKey = apiKey
    }

    /** Test seam: the exact value [refreshNow] would send as `token`. */
    @JvmStatic
    fun cachedApiKeyForTesting(): String? = lock.withLock { cachedApiKey }

    // ---------------------------------------------------------------------------------
    // Origin pinning (invariant 3)
    // ---------------------------------------------------------------------------------

    /**
     * Pins the gateway origin from configuration. [refreshNow] POSTs the permanent, full-scope
     * API key to this origin, so letting an arbitrary URL seen in a style document decide where
     * that goes would hand the customer's key to whoever wrote the style. Once configured the
     * origin is never learned from traffic.
     *
     * WHAT PRODUCTION ACTUALLY DOES, both cases:
     *
     * `MMMapSessionInterceptor.install` reads the
     * `org.maplibre.android.MapSessionOrigin` `<meta-data>` from the host app's
     * `AndroidManifest.xml` during `MapLibre.getInstance` and calls this when it is present. That
     * is the strong case: the origin comes from the app's own manifest and no response can move
     * it.
     *
     * When the meta-data is ABSENT — the default, since `WellKnownTileServer` has no MapMetrics
     * entry and the native `TileServerOptions` carry no gateway host — this is never called and
     * [signedUrl] learns the origin instead, from the first https `/v2/tiles/` URL it sees, once
     * and irrevocably. That is weaker: whichever host serves the first v2 tile is the host the
     * API key is later POSTed to. It is https-only and one-shot, so a later style cannot re-point
     * it, but an app that cares about invariant 3 must set the meta-data.
     */
    @JvmStatic
    fun pinConfiguredOrigin(baseUrl: String?) {
        val url = baseUrl?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return
        lock.withLock {
            origin = originOf(url)
            originIsConfigured = true
        }
    }

    private fun originOf(url: HttpUrl): HttpUrl =
        HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build()

    /** Test seam: the pinned gateway origin, or null if none has been established. */
    @JvmStatic
    fun originForTesting(): HttpUrl? = lock.withLock { origin }

    /**
     * Test seam: how many times the "tile host does not match the pinned origin" diagnostic has
     * been emitted. Must be at most 1 however many tiles are refused — a map view issues
     * hundreds of tile requests and a per-tile log would drown the console.
     */
    @JvmStatic
    fun originMismatchLogCountForTesting(): Int = lock.withLock { originMismatchLogs }

    // ---------------------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------------------

    /**
     * Appends `u/s/e/a/k/sig` if this is a v2 tile URL AND a credential with a known account is
     * held AND the host matches the pinned origin. Returns the input unchanged otherwise, and
     * never throws — an unsigned tile 401s and recovers, a thrown exception kills the request.
     */
    @JvmStatic
    fun signedUrl(url: HttpUrl): HttpUrl {
        try {
            if (!url.encodedPath.contains(TILE_PATH_MARKER)) return url

            var logMismatch = false
            val requestHost = url.host
            var originHost: String? = null
            var params: List<Pair<String, String>>? = null

            lock.withLock {
                // Learn the gateway origin from the tile URL itself when nothing is
                // configured, so the SDK works against staging and production with no setup.
                // Two constraints make that safe enough to keep: https only (the API key is
                // POSTed here later), and learned exactly ONCE — a later tile URL on a
                // different host can never re-point the origin.
                if (origin == null && url.scheme == "https" && url.host.isNotEmpty()) {
                    origin = originOf(url)
                }
                val pinned = origin
                originHost = pinned?.host
                val sameOrigin = pinned != null && url.host == pinned.host

                // A credential is only usable once we know which account it belongs to: the
                // account is inside the HMAC payload the gateway signs, so an empty `u` can
                // never verify. The gateway calls that malformed and 401s — and 401 is now
                // retryable, so signing with an unknown account would be an infinite retry
                // loop of blank tiles instead of the recoverable "not signed yet" state.
                val haveCredential = sameOrigin &&
                    sig != null && sessionId != null && !account.isNullOrEmpty()

                // Refusing to sign because of a host mismatch is a CONFIGURATION fault and is
                // otherwise entirely silent: every tile goes out unsigned, the gateway 401s,
                // and the session-identity gate correctly declines to buy a window for a 401
                // that is not about our credential — so the map is blank forever with nothing
                // logged. Say so, ONCE.
                if (pinned != null && !sameOrigin && !loggedOriginMismatch) {
                    loggedOriginMismatch = true
                    originMismatchLogs++
                    logMismatch = true
                }

                if (haveCredential) {
                    // A request the SDK could ACTUALLY sign counts as billable use of the
                    // current window — this, and only this, authorises the renewal timer to
                    // fire. A request we could not sign is not billable use. (Invariant 1.)
                    activity = true
                    params = listOf(
                        "u" to account!!,
                        "s" to sessionId!!,
                        "e" to exp.toString(),
                        "a" to sae.toString(),
                        "k" to (keyId ?: "1"),
                        "sig" to sig!!
                    )
                }
            }

            if (logMismatch) {
                // Outside the lock: logging is foreign code and must never run with our lock held.
                Logger.e(
                    TAG,
                    "tile host \"$requestHost\" does not match the map-session origin " +
                        "\"${originHost ?: "(none)"}\", so tiles will NOT be signed and the map " +
                        "will stay blank. Point the tile server base URL at the gateway host."
                )
            }

            val items = params ?: return url

            // MERGE, never replace. The URL may already carry query items the gateway or the
            // style depends on. Any stale copy of our OWN params is dropped first so
            // re-signing the same URL cannot duplicate them. (Invariant 5.)
            val builder = url.newBuilder()
            CREDENTIAL_PARAMS.forEach { builder.removeAllQueryParameters(it) }
            items.forEach { (name, value) -> builder.addQueryParameter(name, value) }
            return builder.build()
        } catch (throwable: Throwable) {
            Logger.e(TAG, "failed to sign tile URL, sending it unsigned", throwable)
            return url
        }
    }

    // ---------------------------------------------------------------------------------
    // Rollover adoption
    // ---------------------------------------------------------------------------------

    /**
     * Adopts a credential from `X-Map-Session-*` response headers (rollover only). Returns true
     * only if the header set is complete, the credential is newer, the responding host is the
     * pinned origin, and the account is already known.
     *
     * @param responseUrl the URL that produced these headers. REQUIRED, and deliberately not
     * defaulted: a host named in a style document could otherwise return `X-Map-Session-*`
     * headers and own the SDK's credential. The only way to skip the check is to call
     * [applyCredentialFromHeadersUnvalidatedForTesting], which is impossible to reach by accident.
     */
    @JvmStatic
    fun applyCredentialFromHeaders(headers: Headers, responseUrl: HttpUrl): Boolean =
        applyCredentialFromHeadersInternal(headers, responseUrl)

    /**
     * Adoption with NO origin validation. Test seam only — never call this from production code.
     */
    @JvmStatic
    fun applyCredentialFromHeadersUnvalidatedForTesting(headers: Headers): Boolean =
        applyCredentialFromHeadersInternal(headers, null)

    private fun applyCredentialFromHeadersInternal(
        headers: Headers,
        responseUrl: HttpUrl?
    ): Boolean {
        val sid = headers["X-Map-Session-Id"]
        val newSig = headers["X-Map-Session-Sig"]
        val newExp = headers["X-Map-Session-Exp"]
        val newEnds = headers["X-Map-Session-Ends"]
        val newKeyId = headers["X-Map-Session-Key-Id"]
        // All five or nothing. A partial set is a rollover we cannot use, and storing half a
        // credential would sign every later tile invalidly.
        if (sid.isNullOrEmpty() || newSig.isNullOrEmpty() || newExp.isNullOrEmpty() ||
            newEnds.isNullOrEmpty() || newKeyId.isNullOrEmpty()
        ) {
            return false
        }
        val expValue = newExp.toDoubleOrNull()?.toLong() ?: return false
        val saeValue = newEnds.toDoubleOrNull()?.toLong() ?: return false

        val newer = lock.withLock {
            // Only the pinned gateway may mint credentials.
            val pinned = origin
            if (pinned != null && responseUrl != null && responseUrl.host != pinned.host) {
                return@withLock false
            }
            // A rollover only ever replaces sessionId/sig/exp/sae for the account that created
            // the session — that is why these headers carry no account of their own. Without a
            // known account we cannot say who this rollover belongs to, and signing with an
            // empty `u` is rejected by the gateway as malformed. (Invariant 6.)
            if (account.isNullOrEmpty()) return@withLock false
            if (expValue <= exp) return@withLock false

            sessionId = sid
            sig = newSig
            keyId = newKeyId
            exp = expValue
            sae = saeValue
            // A new window starts with no use recorded — otherwise one pan would authorise
            // renewing forever and the idle-billing bug returns by the back door. (Invariant 1.)
            activity = false
            // A working credential clears the give-up state. (Invariant 4.)
            hardFailures = 0
            lastCountedFailureAt = 0
            gaveUp = false
            gaveUpAt = 0
            true
        }
        if (newer) scheduleRenewal()
        return newer
    }

    /**
     * Whether a 401 for this response URL should trigger a refresh. (Invariant 2.)
     *
     * Many tiles are in flight at once, so a signing-key rotation 401s all of them. Without this
     * gate the first 401 buys a new credential and every straggler — still carrying the now-dead
     * session id — buys ANOTHER billed one. `refreshInFlight` cannot help: the responses arrive
     * serialised, each after the previous refresh has already completed.
     */
    @JvmStatic
    fun shouldRefreshForResponseUrl(url: HttpUrl): Boolean {
        val responseSessionId = url.queryParameter("s")
        return lock.withLock {
            if (responseSessionId.isNullOrEmpty()) {
                // An unsigned tile 401ing is the cold-start bootstrap. If we DO hold a
                // credential, an unsigned request is none of our business and must not buy
                // a window.
                sig == null
            } else {
                // Otherwise: stale news about a credential we have already replaced.
                responseSessionId == sessionId
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Create / renew
    // ---------------------------------------------------------------------------------

    /** Creates or renews. Asynchronous; concurrent callers coalesce onto one request. */
    @JvmStatic
    fun refreshNow() {
        val now = nowSeconds()
        var snapshotOrigin: HttpUrl? = null
        var canRenew = false
        var snapAccount: String? = null
        var snapSession: String? = null
        var snapSig: String? = null
        var snapKeyId: String? = null
        var snapExp = 0L
        var snapSae = 0L
        var apiKey: String? = null

        lock.withLock {
            // Give-up is a pause, not a verdict. (Invariant 4, the cool-down way back.)
            if (gaveUp && now - gaveUpAt >= GIVE_UP_COOLDOWN_SECONDS) {
                gaveUp = false
                gaveUpAt = 0
                hardFailures = 0
                lastCountedFailureAt = 0
            }
            if (refreshInFlight || gaveUp) return
            // Counted after the suppression gates and before the origin check, so a test can
            // observe "a refresh was warranted and not suppressed" without any traffic. Every
            // increment here is a BILLED map load in production.
            refreshCalls++
            val pinned = origin ?: return
            refreshInFlight = true
            snapshotOrigin = pinned
            canRenew = sig != null && sae > now
            snapAccount = account
            snapSession = sessionId
            snapSig = sig
            snapKeyId = keyId
            snapExp = exp
            snapSae = sae
            // The API key is read from the CACHE, never from MapLibre.getApiKey(). This runs on
            // an OkHttp dispatcher thread, and MapLibre is @UiThread by contract, so the refresh
            // path must never reach into its static state: the key is PUSHED in via
            // [cacheApiKey]. (getApiKey() is a plain field read today and does NOT throw off the
            // main thread — but that is an implementation detail, not a contract to depend on.)
            apiKey = cachedApiKey
        }

        val base = snapshotOrigin ?: return
        val url = if (canRenew) {
            base.newBuilder()
                .encodedPath("/v2/map-sessions/renew")
                .addQueryParameter("u", snapAccount ?: "")
                .addQueryParameter("s", snapSession ?: "")
                .addQueryParameter("e", snapExp.toString())
                .addQueryParameter("a", snapSae.toString())
                .addQueryParameter("k", snapKeyId ?: "1")
                .addQueryParameter("sig", snapSig ?: "")
                .build()
        } else {
            base.newBuilder()
                .encodedPath("/v2/map-sessions")
                .addQueryParameter("token", apiKey ?: "")
                .build()
        }

        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody())
            .build()

        try {
            callFactory.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A transport failure is not an auth failure: it must not spend the
                    // hard-failure budget, but it must clear the in-flight flag.
                    handleRefreshFailure(0)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Belt and braces: ANY escape from this callback that skips
                    // handleRefreshFailure leaves refreshInFlight true, which wedges every
                    // future refresh for the life of the process.
                    try {
                        val status = response.code
                        val json = try {
                            response.body?.string()?.takeIf { it.isNotEmpty() }
                                ?.let { JSONObject(it) }
                        } catch (throwable: Throwable) {
                            null
                        } finally {
                            response.close()
                        }
                        if (status == 200 && adoptRefreshResponse(json)) return
                        handleRefreshFailure(status)
                    } catch (throwable: Throwable) {
                        Logger.e(TAG, "map-session refresh response could not be handled", throwable)
                        handleRefreshFailure(0)
                    }
                }
            })
        } catch (throwable: Throwable) {
            handleRefreshFailure(0)
        }
    }

    /**
     * Adopts a `/v2/map-sessions` create/renew response body. Returns false for a body that
     * would yield an unusable credential. (Invariant 6.)
     *
     * A 200 whose body does not carry a FUTURE expiry is not a credential: adopting it yields
     * `exp = 0`, which signs `e=0` on every tile and 401s on every one of them — feeding exactly
     * the repeat-refresh loop [shouldRefreshForResponseUrl] exists to stop. An empty account
     * signs `u=`, which the gateway rejects as malformed, forever.
     */
    @JvmStatic
    fun adoptRefreshResponse(json: JSONObject?): Boolean {
        if (json == null) return false
        val expValue = json.optDouble("expires_at", 0.0).toLong()
        val sid = json.optString("session_id")
        val newSig = json.optString("sig")
        val newAccount = json.optString("account").takeIf { it.isNotEmpty() }
        if (expValue <= nowSeconds() || sid.isEmpty() || newSig.isEmpty()) return false

        lock.withLock {
            if (newAccount == null && account.isNullOrEmpty()) return false
            account = newAccount ?: account
            sessionId = sid
            sig = newSig
            keyId = json.optString("key_id").takeIf { it.isNotEmpty() } ?: "1"
            exp = expValue
            sae = json.optDouble("session_ends_at", 0.0).toLong()
            activity = false // a new window starts with no use recorded (invariant 1)
            refreshInFlight = false
            hardFailures = 0 // success clears the consecutive-failure count (invariant 4)
            lastCountedFailureAt = 0
            gaveUp = false
            gaveUpAt = 0
        }
        scheduleRenewal()
        return true
    }

    /**
     * Handles a non-adoptable refresh response. (Invariant 4.)
     *
     * 401 past grace means "create a new one"; 403 means the key is not permitted to do this at
     * all. Both must drop the credential: leaving it in place keeps the renew branch reachable,
     * so every later refresh fails the same way forever.
     */
    @JvmStatic
    fun handleRefreshFailure(status: Int) {
        val hard = status == 401 || status == 403
        val now = nowSeconds()
        var givingUp = false
        var failures = 0
        lock.withLock {
            if (hard) {
                sig = null
                sessionId = null
                exp = 0
                // The COUNT only advances for failures far enough apart to be separate
                // attempts. A burst of 401s from tiles that were all in flight together is
                // one incident, not three.
                if (now - lastCountedFailureAt >= MIN_HARD_FAILURE_SPACING_SECONDS) {
                    hardFailures++
                    lastCountedFailureAt = now
                }
            }
            givingUp = hard && !gaveUp && hardFailures >= MAX_CONSECUTIVE_HARD_FAILURES
            if (givingUp) {
                gaveUp = true
                gaveUpAt = now
            }
            failures = hardFailures
            refreshInFlight = false
        }
        if (givingUp) {
            Logger.e(
                TAG,
                "giving up after $failures consecutive map-session auth failures (last status " +
                    "$status). Tiles will not be signed. Check that the API key is set and " +
                    "permitted to create v2 map sessions. Refreshing resumes when the app next " +
                    "enters the foreground, or after $GIVE_UP_COOLDOWN_SECONDS seconds."
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Activity-gated renewal (invariant 1)
    // ---------------------------------------------------------------------------------

    @JvmStatic
    val secondsUntilExpiry: Long
        get() = lock.withLock {
            val left = if (exp > 0) exp - nowSeconds() else 0
            if (left > 0) left else 0
        }

    @JvmStatic
    val hasActivitySinceCredentialIssued: Boolean
        get() = lock.withLock { activity }

    /**
     * THE IDLE GATE. Renewal costs a map load, so it must be paid for by USE.
     *
     * A map left open and untouched requests no tiles, so it costs the platform nothing.
     * Renewing it anyway would bill ~16 map loads overnight for zero requests — worse than v1,
     * which billed only on a cookieless tile. With no activity we let the credential lapse; the
     * next real tile rolls over at the gateway and is billed then, which is the correct moment.
     */
    @JvmStatic
    fun shouldRenewNow(): Boolean = hasActivitySinceCredentialIssued

    private fun secondsUntilNextRenewal(): Long {
        val lead = if (renewLeadTimeSeconds > 0) renewLeadTimeSeconds else 60
        val left = secondsUntilExpiry - lead
        return if (left > 0) left else 0
    }

    /**
     * Cancels any armed renewal task and arms the next one, atomically.
     *
     * Callers are genuinely concurrent (the network thread via [applyCredentialFromHeaders], the
     * OkHttp dispatcher via the refresh completion, the main thread via the foreground hook, and
     * the timer's own body). With the lock dropped in between, two callers could interleave as
     * cancel/cancel/arm-A/arm-B and leave task A alive but unreferenced — it would then fire on
     * a superseded schedule and, if there had been activity since, buy a BILLED window nobody
     * asked for. So EVERY path through here cancels, including the "renew immediately" one.
     */
    @JvmStatic
    fun scheduleRenewal() {
        // Computed before the lock: these accessors take it themselves.
        val delay = secondsUntilNextRenewal()

        lock.withLock {
            timer?.cancel(false)
            timer = null
            if (delay > 0) {
                timer = scheduler.schedule(
                    {
                        // Re-checked AT FIRE TIME, not at schedule time: a map that was in use
                        // when the timer was set may have gone idle since.
                        if (shouldRenewNow()) refreshNow()
                    },
                    delay,
                    TimeUnit.SECONDS
                )
            }
        }

        // Outside the lock: refreshNow takes it, and must never be called with it held.
        if (delay <= 0 && shouldRenewNow()) refreshNow()
    }

    /**
     * The foreground hook, wired up by `MapLibre.getInstance`. A backgrounded app does not fire
     * scheduled tasks, so on return the credential may already be past `exp`. This is also the
     * fast way out of the give-up state (invariant 4): once given up, nothing else can clear it
     * in practice, because the resets need traffic that can no longer happen.
     */
    @JvmStatic
    fun onEnterForeground() {
        lock.withLock {
            gaveUp = false
            gaveUpAt = 0
            hardFailures = 0
            lastCountedFailureAt = 0
        }
        scheduleRenewal()
    }

    // ---------------------------------------------------------------------------------
    // Test seams
    // ---------------------------------------------------------------------------------

    /** Seeds the account the way a create response normally would, without a round trip. */
    @JvmStatic
    fun seedAccountForTesting(newAccount: String?) = lock.withLock {
        account = newAccount
    }

    /** Seeds a full credential without a round trip. */
    @JvmStatic
    fun seedCredentialForTesting(
        newAccount: String,
        newSessionId: String,
        newSig: String,
        newExp: Long,
        newSae: Long,
        newKeyId: String = "1"
    ) = lock.withLock {
        account = newAccount
        sessionId = newSessionId
        sig = newSig
        exp = newExp
        sae = newSae
        keyId = newKeyId
        activity = false
    }

    /** True if [scheduleRenewal] currently has a live task armed. */
    @JvmStatic
    fun hasPendingTimerForTesting(): Boolean = lock.withLock { timer != null }

    /** True once [MAX_CONSECUTIVE_HARD_FAILURES] hard failures have paused the refresh loop. */
    @JvmStatic
    fun hasGivenUpForTesting(): Boolean = lock.withLock { gaveUp }

    /** How many times a refresh was warranted and not suppressed. Each one is a billed load. */
    @JvmStatic
    fun refreshCallCountForTesting(): Int = lock.withLock { refreshCalls }

    /** True if a credential is currently held. */
    @JvmStatic
    fun hasCredentialForTesting(): Boolean = lock.withLock { sig != null }

    /** Back-dates the failure clocks so spacing and cool-down can be exercised without sleeping. */
    @JvmStatic
    fun rewindFailureClocksForTesting(seconds: Long) = lock.withLock {
        if (lastCountedFailureAt > 0) lastCountedFailureAt -= seconds
        if (gaveUpAt > 0) gaveUpAt -= seconds
    }

    @JvmStatic
    fun resetForTesting() = lock.withLock {
        account = null
        sessionId = null
        sig = null
        keyId = null
        exp = 0
        sae = 0
        refreshInFlight = false
        activity = false
        hardFailures = 0
        gaveUp = false
        gaveUpAt = 0
        lastCountedFailureAt = 0
        refreshCalls = 0
        loggedOriginMismatch = false
        originMismatchLogs = 0
        cachedApiKey = null
        renewLeadTimeSeconds = 60
        if (!originIsConfigured) origin = null
        timer?.cancel(false)
        timer = null
    }

    /** Also drops a configured origin. Separate so [resetForTesting] mirrors the iOS semantics. */
    @JvmStatic
    fun resetOriginForTesting() = lock.withLock {
        origin = null
        originIsConfigured = false
        loggedOriginMismatch = false
        originMismatchLogs = 0
    }
}
