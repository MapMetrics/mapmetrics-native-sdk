package org.maplibre.android.session

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
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

    @Before
    fun setUp() {
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        requests = mutableListOf()
        val slot = slot<Request>()
        val factory = mockk<Call.Factory>()
        every { factory.newCall(capture(slot)) } answers {
            requests.add(slot.captured)
            mockk<Call>(relaxed = true)
        }
        MMMapSession.callFactory = factory
    }

    @After
    fun tearDown() {
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
    }

    private fun now() = System.currentTimeMillis() / 1000L

    private fun tileUrl(host: String = gateway, query: String = ""): HttpUrl =
        "https://$host/v2/tiles/streets/1/2/3.pbf$query".toHttpUrl()

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
        // retryable) retries forever.
        assertFalse(MMMapSession.applyCredentialFromHeaders(rollover(), tileUrl()))
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
        assertEquals(0, MMMapSession.refreshCallCountForTesting())
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
        assertEquals("a given-up session must stop refreshing", 0, MMMapSession.refreshCallCountForTesting())
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
        assertEquals(1, MMMapSession.refreshCallCountForTesting())
    }

    @Test
    fun cooldownClearsGiveUp() {
        giveUp()
        MMMapSession.rewindFailureClocksForTesting(MMMapSession.GIVE_UP_COOLDOWN_SECONDS + 1)
        MMMapSession.refreshNow()
        assertFalse(MMMapSession.hasGivenUpForTesting())
        assertEquals(1, MMMapSession.refreshCallCountForTesting())
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
            MMMapSession.refreshCallCountForTesting()
        )
    }

    @Test
    fun aUsedExpiredCredentialRenewsImmediatelyRatherThanOnATimer() {
        seed(exp = now() + 10)
        MMMapSession.signedUrl(tileUrl()) // records activity, learns origin
        MMMapSession.resetOriginForTesting() // keep the assertion traffic-free
        MMMapSession.scheduleRenewal()
        assertFalse(MMMapSession.hasPendingTimerForTesting())
        assertEquals(1, MMMapSession.refreshCallCountForTesting())
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

    @Test
    fun secondsUntilExpiryIsZeroWithoutACredential() {
        assertEquals(0L, MMMapSession.secondsUntilExpiry)
        seed(exp = now() + 100)
        assertTrue(MMMapSession.secondsUntilExpiry in 95..100)
    }
}
