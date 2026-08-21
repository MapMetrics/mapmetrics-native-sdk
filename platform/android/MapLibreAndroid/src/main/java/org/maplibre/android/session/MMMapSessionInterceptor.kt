package org.maplibre.android.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.RestrictTo
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.module.http.MMHttpClients
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place v2 map sessions touch the network.
 *
 * Android has no per-request delegate — the only hook the SDK offers is
 * [HttpRequestUtil.setOkHttpClient], a whole-client swap — so signing on the way out and
 * credential recovery on the way back both live in this single interceptor.
 */
class MMMapSessionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Signing is a no-op for anything that is not tile-shaped ([MMMapSession.isTileUrl] /
        // [MMMapSession.TILE_PATH_PATTERN], the ONE shared definition — never a literal path here)
        // on the pinned origin, and signedUrl never throws: an unsigned tile 401s and recovers
        // below. Since the gateway now honours a v2 signature on the v1 tile path too, the URL a
        // real style already hands out is signed without the style changing at all.
        // BEFORE signing, and before the first tile. The style request reaches the gateway ahead
        // of every tile, so learning the origin and buying the first window here is what puts a
        // credential in hand for the opening wave. Without it the SDK only ever acquires one by
        // being 401'd, which a v1-shaped `?token=` tile never is -- it returns 200 and bills per
        // tile. No-op once a credential is held, and no-op for any host off the allow-list.
        MMMapSession.noteGatewayRequest(original.url)

        val signed = MMMapSession.signedUrl(original.url)
        val wasSigned = signed != original.url
        val request = if (wasSigned) {
            original.newBuilder().url(signed).build()
        } else {
            original
        }

        val response = chain.proceed(request)

        // X-Map-Session-* arrive ONLY from a rollover: the credential had expired, the gateway
        // minted a replacement AND CHARGED for it.
        //
        // The URL passed to adoption is `response.request.url`, NOT `request.url`. This is an
        // application interceptor, so `request.url` is the URL BEFORE any redirect, while
        // `response.request.url` is the one that actually produced these headers. If the gateway
        // ever 3xx'd cross-host, using the pre-redirect URL would let the redirect TARGET's
        // headers pass an origin check they never actually satisfied.
        val adopted = if (response.header(SESSION_SIG_HEADER) != null) {
            MMMapSession.applyCredentialFromHeaders(response.headers, response.request.url)
        } else {
            false
        }

        // FALL THROUGH. Deliberately not `else if`: a 401 that also carries rollover headers we
        // then REFUSE (unknown account, not newer, wrong host) would otherwise consume the header
        // branch and never trigger recovery, leaving the session machine with no usable credential
        // and nothing scheduled — a permanently blank map.
        //
        // This gate reads `request.url`, not `response.request.url`, and the difference is
        // deliberate: it asks "is this 401 about the credential WE SENT", and what we sent is the
        // URL we signed. A redirect that dropped the query would make the post-redirect URL look
        // unsigned and wrongly suppress recovery.
        if (!adopted &&
            response.code == 401 &&
            MMMapSession.shouldRefreshForResponseUrl(request.url)
        ) {
            MMMapSession.refreshNow()
        }

        if (wasSigned && response.code != 401) {
            // A tile WE SIGNED came back with something other than 401: the gateway honoured the
            // credential. That is what makes the tile-401 budget in
            // [MMMapSession.shouldRefreshForResponseUrl] consecutive rather than cumulative —
            // without it, three isolated and fully recovered 401s hours apart would eventually
            // give up on a perfectly healthy session.
            MMMapSession.noteSignedTileAccepted()
        }

        return response
    }

    companion object {

        private const val TAG = "Mbgl-MMMapSessionInterceptor"

        /** The rollover marker header. Its presence is what makes adoption worth attempting. */
        const val SESSION_SIG_HEADER = "X-Map-Session-Sig"

        /**
         * `AndroidManifest.xml` `<meta-data>` naming the gateway origin, for example:
         *
         * ```xml
         * <meta-data
         *     android:name="org.maplibre.android.MapSessionOrigin"
         *     android:value="https://gateway.example.com" />
         * ```
         *
         * This is the configuration channel for invariant 3. Without it the origin is learned from
         * the first https tile URL, and [MMMapSession.refreshNow] POSTs the customer's permanent
         * API key to whatever that turned out to be.
         */
        const val GATEWAY_ORIGIN_META_DATA = "org.maplibre.android.MapSessionOrigin"

        private val installed = AtomicBoolean(false)
        private val originPinAttempted = AtomicBoolean(false)
        private val foregroundInstalled = AtomicBoolean(false)

        @Volatile
        private var installedClient: OkHttpClient? = null

        /** How many activities are currently started. 0 -> 1 is "the app entered the foreground". */
        private val startedActivities = AtomicInteger(0)

        /**
         * How many activity restarts are owed to a configuration change, so the matching restarts
         * are not counted as new foreground entries. See [activityStarted].
         *
         * A COUNTER, not a flag. A boolean cannot represent two pending restarts: two sets would
         * collapse into one, the second restart would be counted, and [startedActivities] would
         * sit permanently one too high — the app would never look backgrounded again and
         * foreground recovery, the feature's only escape from a blank map, would be dead for the
         * life of the process. Upward drift does not self-heal the way downward drift does.
         * `ActivityThread` relaunches each activity as one synchronous transaction so this is
         * unlikely, but a counter makes it impossible rather than improbable.
         */
        private val pendingConfigChangeRestarts = AtomicInteger(0)

        /**
         * Pins the gateway origin from the manifest, registers a client carrying
         * [MMMapSessionInterceptor] as the SDK's HTTP client, and wires the foreground hook.
         *
         * Idempotent: safe to call from every `MapLibre.getInstance` overload on every call.
         *
         * @param context any context; its application context is used. Null skips the manifest
         * lookup and the foreground hook, leaving only the interceptor.
         */
        @JvmStatic
        @JvmOverloads
        fun install(context: Context? = null) {
            pinOriginFromManifest(context)
            installClient()
            installForegroundHook(context)
        }

        /**
         * Reads [GATEWAY_ORIGIN_META_DATA] and pins it as the gateway origin (invariant 3).
         *
         * Absent meta-data is not an error: the SDK falls back to learning the origin from the
         * first https tile URL, which is what every app does today and what keeps staging and
         * production working with no setup. That fallback is weaker — see
         * [MMMapSession.pinConfiguredOrigin] — which is exactly why this channel exists.
         */
        private fun pinOriginFromManifest(context: Context?) {
            if (context == null) return
            // Once per process. Apps commonly call MapLibre.getInstance from every Activity's
            // onCreate, and getApplicationInfo is a binder round trip on the UI thread; the
            // manifest cannot change while the process is alive, so reading it again is pure cost.
            if (!originPinAttempted.compareAndSet(false, true)) return
            try {
                val appContext = context.applicationContext ?: context
                val info = appContext.packageManager.getApplicationInfo(
                    appContext.packageName,
                    PackageManager.GET_META_DATA
                )
                val configured = info.metaData?.getString(GATEWAY_ORIGIN_META_DATA)?.trim()
                if (!configured.isNullOrEmpty()) {
                    MMMapSession.pinConfiguredOrigin(configured)
                }
            } catch (throwable: Throwable) {
                // A manifest we cannot read must not take getInstance down; the learn-once
                // fallback still produces a working map.
                //
                // But RESET the guard, exactly as installClient does. Leaving it set abandons
                // origin pinning for the life of the process on a single throwing
                // getApplicationInfo, and falls back SILENTLY to learning the origin from
                // traffic — the weaker path, the one that decides where the customer's permanent
                // API key gets POSTed. Apps call getInstance from every Activity's onCreate, so
                // one more attempt costs nothing and usually succeeds.
                originPinAttempted.set(false)
                Logger.e(TAG, "could not read $GATEWAY_ORIGIN_META_DATA from the manifest", throwable)
            }
        }

        /**
         * Installs the signing client, or RE-installs it if something displaced ours.
         *
         * [HttpRequestUtil.setOkHttpClient] is public API and host apps commonly use it (custom
         * certificate pinning, proxies, logging). An app that calls it after
         * `MapLibre.getInstance` silently unhooks signing: every v2 tile then goes out unsigned,
         * 401s, and — 401 being retryable in this fork — retries forever, blank and unlogged.
         * A one-shot AtomicBoolean made that unrecoverable. The iOS sibling re-asserts on every
         * foreground for exactly this reason (MMMapSession.mm, applicationWillEnterForeground),
         * so this is a check, not a latch: it is a no-op unless our client is no longer the one
         * in use.
         */
        private fun installClient() {
            val current = MMHttpClients.currentClient()
            val ours = installedClient
            val alreadyInstalled = ours != null && current === ours
            if (alreadyInstalled) return
            val displaced = ours != null
            try {
                // newBuilder() on the EXISTING client, never a fresh OkHttpClient.Builder(): the
                // default client carries this fork's InMemoryCookieJar, which holds the gateway's
                // usageSession cookie. A fresh client drops it silently — tiles still render, and
                // v1 billing regresses by roughly 200x with nothing logged anywhere.
                //
                // Built from the DEFAULT client rather than from whatever is installed now: an
                // app-supplied client may itself be derived from ours, and chaining onto it would
                // stack a second interceptor and drive N adoptions and N refreshes per response.
                val client: OkHttpClient = MMHttpClients.defaultClient()
                    .newBuilder()
                    .addInterceptor(MMMapSessionInterceptor())
                    .build()
                // Registered FIRST, and only then recorded as ours. Recording it before the call
                // would leave `installedClient` naming a client that was never installed, and the
                // displacement check above would then be comparing against a phantom.
                HttpRequestUtil.setOkHttpClient(client)
                installedClient = client
                installed.set(true)
                if (displaced) {
                    Logger.w(
                        TAG,
                        "the map-session HTTP client had been replaced via " +
                            "HttpRequestUtil.setOkHttpClient, which unhooks v2 tile signing; " +
                            "re-installed it. A host app that needs its own client should build " +
                            "it from the one this SDK installs."
                    )
                }
            } catch (throwable: Throwable) {
                // Never take the map down over this: without the interceptor, v2 tiles go
                // unsigned, which is the pre-existing v1 behaviour rather than a failure.
                installed.set(false)
                Logger.e(TAG, "failed to install the map-session interceptor", throwable)
            }
        }

        /**
         * [MMMapSession.onEnterForeground] clears the give-up state. Without a caller, a
         * transient auth failure becomes a permanently blank map until the process restarts.
         *
         * `ProcessLifecycleOwner` would be the natural signal, but `androidx.lifecycle:
         * lifecycle-process` is not a dependency of this module and adding one is out of scope,
         * so this counts started activities instead — the same thing that library does.
         */
        private fun installForegroundHook(context: Context?) {
            if (context == null) return
            val application = context.applicationContext as? Application ?: return
            if (!foregroundInstalled.compareAndSet(false, true)) return
            try {
                application.registerActivityLifecycleCallbacks(ForegroundCallbacks)
            } catch (throwable: Throwable) {
                foregroundInstalled.set(false)
                Logger.e(TAG, "failed to install the map-session foreground hook", throwable)
            }
        }

        private fun activityStarted() {
            // A restart that is only the other half of a configuration change is not a foreground
            // entry, and the matching stop already declined to decrement, so this must not
            // increment either — otherwise the count drifts up by one per rotation and the app
            // never looks backgrounded again. Claim one pending restart if there is one.
            while (true) {
                val pending = pendingConfigChangeRestarts.get()
                if (pending <= 0) break
                if (pendingConfigChangeRestarts.compareAndSet(pending, pending - 1)) return
            }

            // Only the 0 -> 1 edge is a foreground transition; every later activity start within
            // the same session is just navigation and must not re-arm anything.
            if (startedActivities.incrementAndGet() == 1) {
                // Re-assert the signing client first: if a host app displaced it while we were
                // backgrounded, everything onEnterForeground re-arms would go out unsigned.
                installClient()
                MMMapSession.onEnterForeground()
            }
        }

        private fun activityStopped(isChangingConfigurations: Boolean) {
            // THE ROTATION GUARD. A configuration change runs stop -> destroy -> create -> start,
            // so a naive counter goes 1 -> 0 -> 1 and fires the foreground edge. onEnterForeground
            // clears gaveUp, hardFailures and lastCountedFailureAt, so a user rotating the device
            // would defeat the three-failure give-up guard entirely and resume hammering a key the
            // gateway will never accept. This is what ProcessLifecycleOwner's debounce exists for.
            if (isChangingConfigurations) {
                pendingConfigChangeRestarts.incrementAndGet()
                return
            }
            if (startedActivities.decrementAndGet() <= 0) {
                startedActivities.set(0)
                // NO PENDING RESTART SURVIVES A BACKGROUND. An Activity that reported
                // isChangingConfigurations and was then never restarted — finished during the
                // rotation, or rotate-then-Home — leaves the counter permanently elevated, so the
                // NEXT genuine foreground entry is consumed by it and onEnterForeground never
                // fires. That kills the only fast escape from the give-up state. By the time
                // nothing is started there is no restart still owed to anyone.
                pendingConfigChangeRestarts.set(0)
            }
        }

        /** Test seam: the client [install] registered, or null if it has not run. */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun installedClientForTesting(): OkHttpClient? = installedClient

        /**
         * Test seam: lets a test install again against a fresh client. Restricted because calling
         * it from an app would unhook signing and blank every v2 map in the process.
         */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun resetInstallStateForTesting() {
            installed.set(false)
            originPinAttempted.set(false)
            foregroundInstalled.set(false)
            startedActivities.set(0)
            pendingConfigChangeRestarts.set(0)
            installedClient = null
            HttpRequestUtil.setOkHttpClient(null)
        }

        /** Test seam: drives an activity start without an Activity. */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun notifyActivityStartedForTesting() = activityStarted()

        /** Test seam: drives an activity stop, optionally as half of a configuration change. */
        @JvmStatic
        @JvmOverloads
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun notifyActivityStoppedForTesting(isChangingConfigurations: Boolean = false) =
            activityStopped(isChangingConfigurations)

        private object ForegroundCallbacks : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) = activityStarted()

            override fun onActivityStopped(activity: Activity) =
                activityStopped(activity.isChangingConfigurations)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    }
}
