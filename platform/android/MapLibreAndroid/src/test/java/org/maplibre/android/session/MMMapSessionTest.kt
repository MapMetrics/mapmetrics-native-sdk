package org.maplibre.android.session

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every test here pins one of the invariants the iOS reference established over five review
 * rounds. Each is written so that removing the invariant makes the test fail.
 */
@RunWith(RobolectricTestRunner::class)
class MMMapSessionTest {

    private val gateway = "gw.example.com"
    private lateinit var requests: MutableList<Request>
    private lateinit var callbacks: MutableList<Callback>

    private lateinit var originalCallFactory: Call.Factory

    @Before
    fun setUp() {
        // Captured BEFORE it is replaced: leaving a mock installed leaks into every later test
        // class in the same JVM, where a refresh would silently enqueue onto a dead factory.
        originalCallFactory = MMMapSession.callFactory
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        requests = mutableListOf()
        callbacks = mutableListOf()
        val requestSlot = slot<Request>()
        val callbackSlot = slot<Callback>()
        val factory = mockk<Call.Factory>()
        every { factory.newCall(capture(requestSlot)) } answers {
            requests.add(requestSlot.captured)
            val call = mockk<Call>(relaxed = true)
            every { call.enqueue(capture(callbackSlot)) } answers {
                callbacks.add(callbackSlot.captured)
            }
            call
        }
        MMMapSession.callFactory = factory
    }

    /** Delivers a response to the callback the n-th refresh handed to OkHttp. */
    private fun deliver(index: Int, code: Int, body: String) {
        val request = requests[index]
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        callbacks[index].onResponse(mockk(relaxed = true), response)
    }

    @After
    fun tearDown() {
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        MMMapSession.callFactory = originalCallFactory
    }

    private fun now() = System.currentTimeMillis() / 1000L

    /**
     * The `/v2/tiles/` form. Real, not invented: `{z}/{x}/{y}.mvt` under the v2 prefix is exactly
     * what the staging gateway serves. An earlier draft used `/v2/tiles/streets/1/2/3.pbf`, which
     * is not a shape any MapMetrics gateway ever emits — it passed only because the matcher was a
     * prefix test and looked at nothing after `/v2/tiles/`.
     */
    private fun tileUrl(host: String = gateway, query: String = ""): HttpUrl =
        "https://$host/v2/tiles/1/2/3.mvt$query".toHttpUrl()

    /**
     * The v1 form a REAL style hands out today — no `/v2/` anywhere, and already carrying a
     * `token` JWT. Signing this is the whole point of the shape matcher: the customer moves to
     * session billing with no style change.
     */
    private fun v1TileUrl(host: String = gateway, query: String = ""): HttpUrl =
        "https://$host/planet20251013/12/2094/1362.mvt$query".toHttpUrl()

    private fun seed(exp: Long = now() + 3600, sae: Long = now() + 86400, session: String = "S1") =
        MMMapSession.seedCredentialForTesting("acct-1", session, "SIG1", exp, sae)

    private fun rollover(
        sid: String = "S2",
        exp: Long = now() + 7200,
        ends: Long = now() + 86400,
        sig: String = "SIG2",
        keyId: String = "2"
    ): Headers = Headers.headersOf(
        "X-Map-Session-Id", sid,
        "X-Map-Session-Sig", sig,
        "X-Map-Session-Exp", exp.toString(),
        "X-Map-Session-Ends", ends.toString(),
        "X-Map-Session-Key-Id", keyId
    )

    // -----------------------------------------------------------------------------
    // Signing basics
    // -----------------------------------------------------------------------------

    @Test
    fun nonTileUrlIsUntouched() {
        seed()
        val url = "https://$gateway/styles/basic.json".toHttpUrl()
        assertSame(url, MMMapSession.signedUrl(url))
        assertNull("a non-tile URL must not teach us an origin", MMMapSession.originForTesting())
    }

    @Test
    fun tileUrlWithoutCredentialIsUntouchedButTeachesTheOrigin() {
        val url = tileUrl()
        assertSame(url, MMMapSession.signedUrl(url))
        assertEquals(gateway, MMMapSession.originForTesting()?.host)
    }

    @Test
    fun signingAppendsAllSixShortFormParams() {
        val exp = now() + 3600
        val sae = now() + 86400
        seed(exp = exp, sae = sae)
        val signed = MMMapSession.signedUrl(tileUrl())
        assertEquals("acct-1", signed.queryParameter("u"))
        assertEquals("S1", signed.queryParameter("s"))
        assertEquals(exp.toString(), signed.queryParameter("e"))
        assertEquals(sae.toString(), signed.queryParameter("a"))
        assertEquals("1", signed.queryParameter("k"))
        assertEquals("SIG1", signed.queryParameter("sig"))
    }

    /** Invariant 5: merge query params, never replace, and never duplicate our own. */
    @Test
    fun existingQueryParamsSurviveAndResigningDoesNotDuplicate() {
        seed()
        val once = MMMapSession.signedUrl(tileUrl(query = "?style=dark&u=stale&sig=stale"))
        assertEquals("dark", once.queryParameter("style"))
        val twice = MMMapSession.signedUrl(once)
        assertEquals("dark", twice.queryParameter("style"))
        for (name in listOf("u", "s", "e", "a", "k", "sig")) {
            assertEquals(
                "re-signing must not duplicate $name",
                1,
                twice.queryParameterValues(name).size
            )
        }
        assertEquals("acct-1", twice.queryParameter("u"))
        assertEquals("SIG1", twice.queryParameter("sig"))
    }

    // -----------------------------------------------------------------------------
    // Invariant 6: adopt only complete, newer, account-known credentials
    // -----------------------------------------------------------------------------

    @Test
    fun completeRolloverHeadersWithKnownAccountAreAdopted() {
        seed()
        MMMapSession.signedUrl(tileUrl()) // learn origin
        assertTrue(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))
        val signed = MMMapSession.signedUrl(tileUrl())
        assertEquals("S2", signed.queryParameter("s"))
        assertEquals("SIG2", signed.queryParameter("sig"))
        assertEquals("2", signed.queryParameter("k"))
    }

    @Test
    fun partialRolloverHeadersAreRejected() {
        seed()
        val partial = Headers.headersOf(
            "X-Map-Session-Id", "S2",
            "X-Map-Session-Sig", "SIG2",
            "X-Map-Session-Exp", (now() + 7200).toString()
        )
        assertFalse(MMMapSession.applyCredentialFromHeaders(partial, tileUrl()))
        assertEquals("S1", MMMapSession.signedUrl(tileUrl()).queryParameter("s"))
    }

    @Test
    fun rolloverWithoutAKnownAccountIsRejected() {
        // No account: signing `u=` is malformed at the gateway, 401s, and (401 now being
        // retryable) retries forever. Asserted through the unvalidated seam so this pins the
        // ACCOUNT gate specifically, independently of the host gate.
        assertFalse(MMMapSession.applyCredentialFromHeadersUnvalidatedForTesting(rollover()))
        assertFalse(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))
        assertFalse(MMMapSession.hasCredentialForTesting())
    }

    @Test
    fun olderRolloverIsRejected() {
        val exp = now() + 7200
        seed(exp = exp)
        assertFalse(MMMapSession.applyCredentialFromHeaders(rollover(exp = exp - 600), tileUrl()))
    }

    @Test
    fun refreshResponseIsRejectedUnlessCompleteAndInTheFuture() {
        MMMapSession.seedAccountForTesting("acct-1")
        assertFalse("past expiry", MMMapSession.adoptRefreshResponse(body(exp = now() - 10)))
        assertFalse("missing expiry", MMMapSession.adoptRefreshResponse(body(exp = 0)))
        assertFalse("empty session id", MMMapSession.adoptRefreshResponse(body(session = "")))
        assertFalse("empty sig", MMMapSession.adoptRefreshResponse(body(sig = "")))
        assertFalse("no credential should have been adopted", MMMapSession.hasCredentialForTesting())
        assertTrue(MMMapSession.adoptRefreshResponse(body()))
        assertTrue(MMMapSession.hasCredentialForTesting())
    }

    @Test
    fun refreshResponseWithNoAccountAnywhereIsRejected() {
        assertFalse(MMMapSession.adoptRefreshResponse(body(account = "")))
        assertFalse(MMMapSession.hasCredentialForTesting())
    }

    private fun body(
        account: String = "acct-1",
        session: String = "S9",
        sig: String = "SIG9",
        exp: Long = now() + 3600,
        ends: Long = now() + 86400
    ): JSONObject = JSONObject()
        .put("account", account)
        .put("session_id", session)
        .put("sig", sig)
        .put("expires_at", exp)
        .put("session_ends_at", ends)
        .put("key_id", "1")

    // -----------------------------------------------------------------------------
    // Invariant 2: ignore a 401 whose s= does not match the held session
    // -----------------------------------------------------------------------------

    @Test
    fun unsignedTile401RefreshesOnlyWhenNoCredentialIsHeld() {
        // Cold start: nothing held, an unsigned 401 is the bootstrap.
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl()))
        seed()
        // Holding one, an unsigned request is none of our business.
        assertFalse(MMMapSession.shouldRefreshForResponseUrl(tileUrl()))
    }

    @Test
    fun a401ForTheHeldSessionRefreshesAndAStaleBurstDoesNot() {
        seed()
        MMMapSession.signedUrl(tileUrl())
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1")))

        // Key rotation: we adopt S2 while eight S1 tiles are still in flight.
        assertTrue(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))
        var billed = 0
        repeat(8) {
            if (MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1"))) {
                MMMapSession.refreshNow()
                billed++
            }
        }
        assertEquals("stale 401s must not each buy a window", 0, billed)
        assertEquals(0, MMMapSession.refreshDecisionCountForTesting())
        // The tile-401 spacing gate (see tileDriven401RefreshesAreSpaced) would otherwise refuse
        // this too, because the S1 401 above already authorised one within the spacing window.
        // This test is about the IDENTITY gate, so step past the spacing gate explicitly.
        MMMapSession.rewindFailureClocksForTesting(
            MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
        )
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S2")))
    }

    // -----------------------------------------------------------------------------
    // Invariant 3: never send the API key to an unvalidated origin
    // -----------------------------------------------------------------------------

    @Test
    fun configuredOriginIsNeverRelearnedFromTraffic() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.signedUrl(tileUrl(host = "evil.example.com"))
        assertEquals(gateway, MMMapSession.originForTesting()?.host)
    }

    @Test
    fun learnedOriginIsNeverRelearnedFromTraffic() {
        MMMapSession.signedUrl(tileUrl())
        MMMapSession.signedUrl(tileUrl(host = "evil.example.com"))
        assertEquals(gateway, MMMapSession.originForTesting()?.host)
    }

    @Test
    fun foreignHostIsNotSignedAndIsLoggedExactlyOnce() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        repeat(100) {
            val url = tileUrl(host = "evil.example.com")
            assertSame(url, MMMapSession.signedUrl(url))
        }
        assertEquals(
            "a map view issues hundreds of tiles; the diagnostic must be logged once",
            1,
            MMMapSession.originMismatchLogCountForTesting()
        )
    }

    @Test
    fun rolloverHeadersFromAForeignHostAreRejected() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        assertFalse(
            MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl(host = "evil.example.com"))
        )
        assertEquals("S1", MMMapSession.signedUrl(tileUrl()).queryParameter("s"))
    }

    @Test
    fun createPostsTheCachedApiKeyToThePinnedOrigin() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.cacheApiKey("KEY-123")
        MMMapSession.refreshNow()
        assertEquals(1, requests.size)
        val url = requests[0].url
        assertEquals("POST", requests[0].method)
        assertEquals(gateway, url.host)
        assertEquals("/v2/map-sessions", url.encodedPath)
        assertEquals("KEY-123", url.queryParameter("token"))
    }

    @Test
    fun renewPostsTheSixCredentialParams() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        val exp = now() + 3600
        val sae = now() + 86400
        seed(exp = exp, sae = sae)
        MMMapSession.refreshNow()
        assertEquals(1, requests.size)
        val url = requests[0].url
        assertEquals("/v2/map-sessions/renew", url.encodedPath)
        assertEquals("acct-1", url.queryParameter("u"))
        assertEquals("S1", url.queryParameter("s"))
        assertEquals(exp.toString(), url.queryParameter("e"))
        assertEquals(sae.toString(), url.queryParameter("a"))
        assertEquals("SIG1", url.queryParameter("sig"))
        assertNull("the API key must never ride on a renew", url.queryParameter("token"))
    }

    // -----------------------------------------------------------------------------
    // Coalescing: concurrent callers ride one request
    // -----------------------------------------------------------------------------

    @Test
    fun concurrentRefreshesCoalesceOntoOneRequest() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        // The cold-start case: several unsigned tiles 401 at once and each drives a refresh.
        repeat(8) { MMMapSession.refreshNow() }
        assertEquals(
            "8 cold-start 401s must buy exactly one session, not eight",
            1,
            requests.size
        )
    }

    @Test
    fun aFailedRefreshReleasesTheInFlightFlagRatherThanWedgingForever() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.refreshNow()
        assertEquals(1, requests.size)
        // A 200 whose body is not a usable credential routes to handleRefreshFailure.
        deliver(0, 200, "this is not json")
        assertFalse(MMMapSession.hasCredentialForTesting())
        MMMapSession.refreshNow()
        assertEquals(
            "the in-flight flag must be released on the failure path, or the SDK never " +
                "refreshes again for the life of the process",
            2,
            requests.size
        )
        // And the happy path releases it too.
        deliver(1, 200, body().toString())
        assertTrue(MMMapSession.hasCredentialForTesting())
        MMMapSession.handleRefreshFailure(401) // drop it so the next refresh is a create
        MMMapSession.refreshNow()
        assertEquals(3, requests.size)
    }

    // -----------------------------------------------------------------------------
    // Invariant 4: bounded consecutive hard failures, with a way back
    // -----------------------------------------------------------------------------

    @Test
    fun burstOfHardFailuresCountsAsOneIncident() {
        repeat(MMMapSession.MAX_CONSECUTIVE_HARD_FAILURES + 5) {
            MMMapSession.handleRefreshFailure(401)
        }
        assertFalse(
            "a burst of in-flight 401s is one incident and must not exhaust the budget",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    @Test
    fun spacedHardFailuresEventuallyGiveUp() {
        repeat(MMMapSession.MAX_CONSECUTIVE_HARD_FAILURES) {
            MMMapSession.handleRefreshFailure(401)
            MMMapSession.rewindFailureClocksForTesting(
                MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
            )
        }
        assertTrue(MMMapSession.hasGivenUpForTesting())
        MMMapSession.refreshNow()
        assertEquals(
            "a given-up session must stop refreshing",
            0,
            MMMapSession.refreshDecisionCountForTesting()
        )
    }

    @Test
    fun softFailuresNeverGiveUp() {
        repeat(20) {
            MMMapSession.handleRefreshFailure(500)
            MMMapSession.rewindFailureClocksForTesting(
                MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
            )
        }
        assertFalse(MMMapSession.hasGivenUpForTesting())
    }

    @Test
    fun hardFailureDropsTheCredentialSoRenewCannotLoopForever() {
        seed()
        MMMapSession.handleRefreshFailure(403)
        assertFalse(MMMapSession.hasCredentialForTesting())
    }

    @Test
    fun foregroundClearsGiveUp() {
        giveUp()
        MMMapSession.onEnterForeground()
        assertFalse(MMMapSession.hasGivenUpForTesting())
        MMMapSession.refreshNow()
        assertEquals(1, MMMapSession.refreshDecisionCountForTesting())
    }

    @Test
    fun cooldownClearsGiveUp() {
        giveUp()
        MMMapSession.rewindFailureClocksForTesting(MMMapSession.GIVE_UP_COOLDOWN_SECONDS + 1)
        MMMapSession.refreshNow()
        assertFalse(MMMapSession.hasGivenUpForTesting())
        assertEquals(1, MMMapSession.refreshDecisionCountForTesting())
    }

    private fun giveUp() {
        repeat(MMMapSession.MAX_CONSECUTIVE_HARD_FAILURES) {
            MMMapSession.handleRefreshFailure(401)
            MMMapSession.rewindFailureClocksForTesting(
                MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
            )
        }
        assertTrue(MMMapSession.hasGivenUpForTesting())
    }

    // -----------------------------------------------------------------------------
    // Invariant 1: never renew an idle map
    // -----------------------------------------------------------------------------

    @Test
    fun freshCredentialReportsNoActivityAndDoesNotRenew() {
        seed()
        assertFalse(MMMapSession.hasActivitySinceCredentialIssued)
        assertFalse(MMMapSession.shouldRenewNow())
    }

    @Test
    fun signingATileRecordsActivity() {
        seed()
        MMMapSession.signedUrl(tileUrl())
        assertTrue(MMMapSession.hasActivitySinceCredentialIssued)
        assertTrue(MMMapSession.shouldRenewNow())
    }

    @Test
    fun aRequestWeCouldNotSignIsNotBillableUse() {
        // No credential at all: the tile goes out unsigned, which is not use of a window.
        MMMapSession.signedUrl(tileUrl())
        assertFalse(MMMapSession.hasActivitySinceCredentialIssued)

        // Host mismatch: refused, and equally not use.
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        MMMapSession.signedUrl(tileUrl(host = "evil.example.com"))
        assertFalse(MMMapSession.hasActivitySinceCredentialIssued)
    }

    @Test
    fun adoptingARolloverResetsActivity() {
        seed()
        MMMapSession.signedUrl(tileUrl())
        assertTrue(MMMapSession.hasActivitySinceCredentialIssued)
        assertTrue(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))
        assertFalse(
            "one pan must not authorise renewing forever",
            MMMapSession.hasActivitySinceCredentialIssued
        )
    }

    @Test
    fun adoptingARefreshResponseResetsActivity() {
        seed()
        MMMapSession.signedUrl(tileUrl())
        assertTrue(MMMapSession.hasActivitySinceCredentialIssued)
        assertTrue(MMMapSession.adoptRefreshResponse(body()))
        assertFalse(MMMapSession.hasActivitySinceCredentialIssued)
    }

    @Test
    fun anIdleExpiredCredentialSchedulesNothingAndBillsNothing() {
        // No origin is established here, so refreshNow counts the decision without traffic.
        seed(exp = now() + 10) // already inside the 60s renew lead
        MMMapSession.scheduleRenewal()
        assertFalse(MMMapSession.hasPendingTimerForTesting())
        assertEquals(
            "an idle map must never buy a window",
            0,
            MMMapSession.refreshDecisionCountForTesting()
        )
    }

    @Test
    fun aUsedExpiredCredentialRenewsImmediatelyRatherThanOnATimer() {
        seed(exp = now() + 10)
        MMMapSession.signedUrl(tileUrl()) // records activity, learns origin
        MMMapSession.resetOriginForTesting() // keep the assertion traffic-free
        MMMapSession.scheduleRenewal()
        assertFalse(MMMapSession.hasPendingTimerForTesting())
        assertEquals(1, MMMapSession.refreshDecisionCountForTesting())
    }

    @Test
    fun aLiveCredentialArmsATimerAndEveryPathCancelsThePreviousOne() {
        seed(exp = now() + 3600)
        MMMapSession.scheduleRenewal()
        assertTrue(MMMapSession.hasPendingTimerForTesting())
        MMMapSession.scheduleRenewal()
        assertTrue(MMMapSession.hasPendingTimerForTesting())
        // The immediate branch must cancel too, or a superseded timer fires later and bills.
        seed(exp = now() + 10)
        MMMapSession.scheduleRenewal()
        assertFalse(
            "the renew-immediately path must cancel the armed timer",
            MMMapSession.hasPendingTimerForTesting()
        )
    }

    // -----------------------------------------------------------------------------
    // C1: a signed tile that keeps 401ing must not buy a credential every time
    // -----------------------------------------------------------------------------

    /**
     * THE UNBOUNDED BILLED LOOP. On the gateway a SIGNED tile only 401s for `malformed` or
     * `bad_signature` — `expired` and `session_ended` take the rollover path and serve the tile.
     * So a signed 401 means a signature or key-id mismatch, and the replacement credential we buy
     * is rejected identically. `/v2/map-sessions[/renew]` ALWAYS bills. Identity matching alone
     * says yes every single time, and the `hardFailures` budget cannot see it because every
     * create in the loop returns 200.
     */
    @Test
    fun aRepeatedlyRejectedSignedTileDoesNotBuyACredentialEveryTime() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.cacheApiKey("KEY")
        seed(session = "S1")

        var authorised = 0
        // 50 tiles come back 401 for the credential we hold, each one a fresh identity match.
        repeat(50) {
            if (MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1"))) authorised++
        }
        assertEquals(
            "a burst of 401s for one credential is one incident, not fifty billed windows",
            1,
            authorised
        )
    }

    @Test
    fun tileDriven401RefreshesAreSpacedAndSpendTheHardFailureBudget() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")

        // First rejection: allowed, and it buys a window.
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1")))
        // Immediately again: same incident, refused.
        assertFalse(
            "a second tile 401 inside the spacing window must not buy another window",
            MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1"))
        )

        // Spaced out, the REPLACEMENT credential is now being rejected too. Allowed once more,
        // but it now costs one unit of the give-up budget.
        MMMapSession.rewindFailureClocksForTesting(
            MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
        )
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1")))
        assertEquals(1, MMMapSession.tile401RefreshCountForTesting())

        MMMapSession.rewindFailureClocksForTesting(
            MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
        )
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1")))
        assertEquals(2, MMMapSession.tile401RefreshCountForTesting())

        // Budget exhausted: the loop stops, permanently until something changes.
        MMMapSession.rewindFailureClocksForTesting(
            MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
        )
        assertFalse(
            "the loop must terminate; every turn of it is a billed map load",
            MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1"))
        )
        assertTrue(MMMapSession.hasGivenUpForTesting())

        // And it really stops spending: refreshNow is suppressed too.
        val before = MMMapSession.refreshDecisionCountForTesting()
        MMMapSession.refreshNow()
        assertEquals(before, MMMapSession.refreshDecisionCountForTesting())
    }

    /**
     * The tile-401 budget must be CONSECUTIVE. Three isolated 401s hours apart, each fully
     * recovered from, are a healthy session — giving up on it would blank a working map.
     */
    @Test
    fun anAcceptedSignedTileClearsTheTile401Budget() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")
        assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1")))

        MMMapSession.noteSignedTileAccepted()
        assertEquals(0, MMMapSession.tile401RefreshCountForTesting())
        // No spacing rewind: a working credential resets the clock as well as the count.
        assertTrue(
            "a recovered session must not be throttled by an old incident",
            MMMapSession.shouldRefreshForResponseUrl(tileUrl(query = "?s=S1"))
        )
    }

    @Test
    fun anUnsignedColdStart401IsNotChargedToTheTile401Budget() {
        // Cold start: repeated unsigned 401s are the bootstrap, not a credential rejection.
        repeat(5) { assertTrue(MMMapSession.shouldRefreshForResponseUrl(tileUrl())) }
        assertEquals(0, MMMapSession.tile401RefreshCountForTesting())
        assertFalse(MMMapSession.hasGivenUpForTesting())
    }

    // -----------------------------------------------------------------------------
    // C2: backgrounding stops the clock on "activity"
    // -----------------------------------------------------------------------------

    /**
     * View a map, background the app, come back HOURS LATER TO ANY SCREEN. `activity` was cleared
     * only on successful adoption, so the foreground hook read a stale `true`, found the delay at
     * zero, and bought a BILLED window for a map that is not on screen and has requested nothing.
     */
    @Test
    fun foregroundingAfterBackgroundingDoesNotBillAnIdleMap() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.cacheApiKey("KEY")
        seed(exp = now() + 10) // inside the 60s renew lead, so the delay is already zero
        MMMapSession.signedUrl(tileUrl()) // the user looked at a map: activity = true
        assertTrue(MMMapSession.hasActivitySinceCredentialIssued)

        MMMapSession.onEnterBackground()
        assertFalse(
            "backgrounding is exactly when use stops",
            MMMapSession.hasActivitySinceCredentialIssued
        )

        val before = MMMapSession.refreshDecisionCountForTesting()
        MMMapSession.onEnterForeground()
        assertEquals(
            "reopening the app to a screen with no map must not buy a window",
            before,
            MMMapSession.refreshDecisionCountForTesting()
        )
        assertEquals(0, requests.size)
    }

    // -----------------------------------------------------------------------------
    // I1: the timer must not buy a second window for a rollover already paid for
    // -----------------------------------------------------------------------------

    /**
     * The timer tests `shouldRenewNow()` at fire time, but between that and taking the lock in
     * `refreshNow` an OkHttp thread can adopt a rollover credential — which the gateway has
     * ALREADY charged for — and reset `activity`. Firing anyway buys a second window for one use.
     */
    @Test
    fun aTimerFiringAfterARolloverDoesNotBuyASecondWindow() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.cacheApiKey("KEY")
        seed(exp = now() + 3600)
        MMMapSession.signedUrl(tileUrl())
        // The rollover lands: a fresh, already-billed credential with plenty of time on it.
        assertTrue(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))

        val before = MMMapSession.refreshDecisionCountForTesting()
        MMMapSession.refreshNowFromTimerForTesting()
        assertEquals(
            "a credential with time still on the clock does not need a timer-driven renewal",
            before,
            MMMapSession.refreshDecisionCountForTesting()
        )

        // The same call against a credential that really is due still goes through.
        seed(exp = now() + 10)
        MMMapSession.signedUrl(tileUrl())
        MMMapSession.refreshNowFromTimerForTesting()
        assertEquals(before + 1, MMMapSession.refreshDecisionCountForTesting())
    }

    // -----------------------------------------------------------------------------
    // I3: a refresh whose callback never fires must not wedge the SDK forever
    // -----------------------------------------------------------------------------

    @Test
    fun aRefreshWhoseCallbackNeverFiresIsEventuallyReleased() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.refreshNow()
        assertEquals(1, requests.size)
        assertTrue(MMMapSession.isRefreshInFlightForTesting())

        // Nothing ever answers. Every later refresh coalesces onto it: blank map, forever.
        MMMapSession.refreshNow()
        assertEquals(1, requests.size)

        MMMapSession.rewindFailureClocksForTesting(MMMapSession.REFRESH_STALE_SECONDS + 1)
        MMMapSession.refreshNow()
        assertEquals(
            "a wedged in-flight flag must be released, or the map is blank until the process " +
                "restarts",
            2,
            requests.size
        )
    }

    @Test
    fun foregroundingReleasesAWedgedRefresh() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.refreshNow()
        assertTrue(MMMapSession.isRefreshInFlightForTesting())
        MMMapSession.rewindFailureClocksForTesting(MMMapSession.REFRESH_STALE_SECONDS + 1)

        MMMapSession.onEnterForeground()
        assertFalse(
            "the fast escape from a blank map must also clear the coalescing gate",
            MMMapSession.isRefreshInFlightForTesting()
        )
    }

    @Test
    fun theDefaultCallFactoryBoundsTheWholeCall() {
        val client = MMMapSession.defaultCallFactory() as OkHttpClient
        assertTrue(
            "a bare OkHttpClient has no call timeout, so a trickling body wedges the SDK forever",
            client.callTimeoutMillis > 0
        )
    }

    // -----------------------------------------------------------------------------
    // I7: session_ends_at is validated to the same standard as expires_at
    // -----------------------------------------------------------------------------

    /**
     * Absent or malformed, `session_ends_at` yields 0, which makes `canRenew` permanently false:
     * every later refresh becomes a CREATE rather than a renew. Silent billing multiplication with
     * no symptom at all — the map keeps working.
     */
    @Test
    fun aCreateResponseWithoutAUsableSessionEndsAtIsRejected() {
        MMMapSession.seedAccountForTesting("acct-1")
        val missing = JSONObject()
            .put("account", "acct-1")
            .put("session_id", "S9")
            .put("sig", "SIG9")
            .put("expires_at", now() + 3600)
        assertFalse("absent session_ends_at", MMMapSession.adoptRefreshResponse(missing))
        assertFalse(
            "past session_ends_at",
            MMMapSession.adoptRefreshResponse(body(ends = now() - 1))
        )
        assertFalse(MMMapSession.hasCredentialForTesting())
        assertTrue(MMMapSession.adoptRefreshResponse(body()))
    }

    @Test
    fun anAdoptedCreateResponseCanThenRenewRatherThanCreateAgain() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        MMMapSession.seedAccountForTesting("acct-1")
        assertTrue(MMMapSession.adoptRefreshResponse(body()))
        MMMapSession.refreshNow()
        assertEquals(
            "a credential with a live session_ends_at must RENEW, not buy a whole new session",
            "/v2/map-sessions/renew",
            requests[0].url.encodedPath
        )
    }

    // -----------------------------------------------------------------------------
    // I4: origin identity is scheme + host + port, not host alone
    // -----------------------------------------------------------------------------

    @Test
    fun aPlaintextTileOnThePinnedHostIsNotSigned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val plaintext = "http://$gateway/v2/tiles/1/2/3.mvt".toHttpUrl()
        assertSame(
            "signing http would put sig on the wire in cleartext",
            plaintext,
            MMMapSession.signedUrl(plaintext)
        )
        assertFalse(
            "a request we refused to sign is not billable use",
            MMMapSession.hasActivitySinceCredentialIssued
        )
    }

    @Test
    fun rolloverHeadersFromAPlaintextResponseAreRejected() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        assertFalse(
            "anyone on the path can forge headers on a plaintext response",
            MMMapSession.applyCredentialFromHeaders(
                rollover(),
                "http://$gateway/v2/tiles/1/2/3.mvt".toHttpUrl()
            )
        )
        assertEquals("S1", MMMapSession.signedUrl(tileUrl()).queryParameter("s"))
    }

    @Test
    fun aTileOnADifferentPortIsNotSigned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val otherPort = "https://$gateway:8443/v2/tiles/1/2/3.mvt".toHttpUrl()
        assertSame(otherPort, MMMapSession.signedUrl(otherPort))
    }

    // -----------------------------------------------------------------------------
    // M6: the tile match is a SHAPE — {z}/{x}/{y}.mvt — not a /v2/tiles/ prefix
    // -----------------------------------------------------------------------------

    /**
     * THE POINT OF THE WIDENING. The gateway now honours a v2 session signature on the EXISTING v1
     * tile path, so a new SDK can sign the URL the style ALREADY gave it and the customer moves to
     * session billing with no style change and no coordination. Under the old
     * `startsWith("/v2/tiles/")` test this URL was ignored and the feature never engaged at all.
     */
    @Test
    fun aV1ShapedTileUrlIsSigned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        val exp = now() + 3600
        val sae = now() + 86400
        seed(exp = exp, sae = sae)
        val signed = MMMapSession.signedUrl(v1TileUrl())
        assertEquals("acct-1", signed.queryParameter("u"))
        assertEquals("S1", signed.queryParameter("s"))
        assertEquals(exp.toString(), signed.queryParameter("e"))
        assertEquals(sae.toString(), signed.queryParameter("a"))
        assertEquals("1", signed.queryParameter("k"))
        assertEquals("SIG1", signed.queryParameter("sig"))
        assertTrue(
            "a request the SDK could actually sign is billable use",
            MMMapSession.hasActivitySinceCredentialIssued
        )
    }

    /** And the form that already worked must not regress on the way. */
    @Test
    fun theV2TilesFormIsStillSigned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val signed = MMMapSession.signedUrl(tileUrl())
        assertEquals("S1", signed.queryParameter("s"))
        assertEquals("SIG1", signed.queryParameter("sig"))
    }

    /**
     * Invariant 5 on the URL that actually matters. A v1 tile URL arrives carrying `?token=<JWT>`,
     * which is what makes it work TODAY. Replacing the query instead of merging into it would
     * strip the token, and a customer mid-migration would go dark. The gateway sees `sig` and
     * takes the session path; the token riding along is harmless.
     */
    @Test
    fun aV1TokenQueryParamSurvivesSigning() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val signed = MMMapSession.signedUrl(v1TileUrl(query = "?token=JWT-123"))
        assertEquals(
            "the style's token must survive alongside the credential params",
            "JWT-123",
            signed.queryParameter("token")
        )
        for (name in listOf("u", "s", "e", "a", "k", "sig")) {
            assertEquals(
                "all six credential params must ride alongside the token; $name is missing",
                1,
                signed.queryParameterValues(name).size
            )
        }
    }

    /**
     * M4, restated for the shape matcher. `startsWith` used to make this impossible; a suffix
     * shape does not, so the cases have to be asserted directly. Nothing here is tile-SHAPED, so
     * none of it may be signed and — the part that actually matters — none of it may teach us the
     * origin the permanent API key is later POSTed to.
     */
    @Test
    fun nonTileShapedUrlsAreUntouchedAndTeachNoOrigin() {
        seed()
        val notTiles = listOf(
            // A style document.
            "https://cdn.example.com/planet20251013/style.json",
            // Adjacent to a real tile: same three numeric segments, wrong extension.
            "https://cdn.example.com/planet20251013/12/2094/1362.json",
            // .mvt, but nothing like three numeric segments in front of it.
            "https://cdn.example.com/planet20251013/streets/tiles.mvt",
            // Only two numeric segments.
            "https://cdn.example.com/planet/2094/1362.mvt",
            // The M4 case verbatim: a foreign CDN proxying something under /v2/tiles/.
            "https://cdn.example.com/proxy/v2/tiles/index.json"
        )
        for (raw in notTiles) {
            val url = raw.toHttpUrl()
            assertSame("$raw must not be signed", url, MMMapSession.signedUrl(url))
        }
        assertNull(
            "nothing non-tile-shaped may become the host the API key is POSTed to",
            MMMapSession.originForTesting()
        )
        assertFalse(
            "a request we never signed is not billable use",
            MMMapSession.hasActivitySinceCredentialIssued
        )
    }

    /**
     * The widened matcher decides only what MIGHT be looked at. The ORIGIN check still decides
     * what is trusted, and it now carries more of the weight — so it is asserted on the newly
     * matched shape specifically, not just on the v2 one.
     */
    @Test
    fun aV1ShapedTileOnAForeignHostIsRefusedAndLoggedExactlyOnce() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        repeat(100) {
            val url = v1TileUrl(host = "evil.example.com", query = "?token=JWT-123")
            assertSame(url, MMMapSession.signedUrl(url))
        }
        assertEquals(
            "a map view issues hundreds of tiles; the diagnostic must be logged once",
            1,
            MMMapSession.originMismatchLogCountForTesting()
        )
        assertFalse(
            "a tile we refused to sign is not billable use",
            MMMapSession.hasActivitySinceCredentialIssued
        )
        assertEquals(
            "a foreign tile-shaped URL must not re-point the pinned origin",
            gateway,
            MMMapSession.originForTesting()?.host
        )
    }

    // -----------------------------------------------------------------------------
    // Diagnostics: the weak origin path must be distinguishable at runtime
    // -----------------------------------------------------------------------------

    @Test
    fun learningTheOriginIsLoggedExactlyOnce() {
        repeat(50) { MMMapSession.signedUrl(tileUrl()) }
        assertEquals(1, MMMapSession.originLearnedLogCountForTesting())
    }

    @Test
    fun aConfiguredOriginIsNeverReportedAsLearned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        repeat(10) { MMMapSession.signedUrl(tileUrl()) }
        assertEquals(0, MMMapSession.originLearnedLogCountForTesting())
    }

    @Test
    fun secondsUntilExpiryIsZeroWithoutACredential() {
        assertEquals(0L, MMMapSession.secondsUntilExpiry)
        seed(exp = now() + 100)
        assertTrue(MMMapSession.secondsUntilExpiry in 95..100)
    }
}
