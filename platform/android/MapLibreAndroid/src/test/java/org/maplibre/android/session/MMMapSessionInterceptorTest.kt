package org.maplibre.android.session

import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.MapLibreInjector
import org.maplibre.android.module.http.MMHttpClients
import org.maplibre.android.utils.ConfigUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The interceptor is the only place v2 sessions touch the network, so these tests pin the four
 * things it must do and the one thing it must not stop doing (billing via the cookie jar).
 */
@RunWith(RobolectricTestRunner::class)
class MMMapSessionInterceptorTest {

    private val gateway = "gw.example.com"
    private val interceptor = MMMapSessionInterceptor()

    @Before
    fun setUp() {
        // HttpRequestImpl's static initialiser reaches MapLibre.getApplicationContext(), so the
        // SDK must be initialised before anything touches the default client. In production this
        // is guaranteed: install() runs from getInstance, after INSTANCE is assigned.
        MapLibreInjector.inject(mockk(relaxed = true), "", ConfigUtils.getMockedOptions())
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        MMMapSessionInterceptor.resetInstallStateForTesting()
        // No traffic leaves the JVM: refreshNow enqueues onto this and nothing ever answers.
        val requestSlot = slot<Request>()
        val factory = mockk<Call.Factory>()
        every { factory.newCall(capture(requestSlot)) } answers {
            mockk<Call>(relaxed = true)
        }
        MMMapSession.callFactory = factory
    }

    @After
    fun tearDown() {
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        MMMapSessionInterceptor.resetInstallStateForTesting()
        MapLibreInjector.clear()
    }

    private fun now() = System.currentTimeMillis() / 1000L

    /** Writes (or clears) the gateway-origin `<meta-data>` the host app would declare. */
    private fun setGatewayMetaData(value: String?) {
        val application = RuntimeEnvironment.getApplication()
        val packageInfo = shadowOf(application.packageManager)
            .getInternalMutablePackageInfo(application.packageName)
        packageInfo.applicationInfo!!.metaData = if (value == null) {
            null
        } else {
            Bundle().apply { putString(MMMapSessionInterceptor.GATEWAY_ORIGIN_META_DATA, value) }
        }
    }

    private fun seed(session: String = "S1") = MMMapSession.seedCredentialForTesting(
        "acct-1",
        session,
        "SIG1",
        now() + 3600,
        now() + 86400
    )

    /**
     * A chain that records the request it was asked to proceed with and returns a canned response.
     * MockWebServer is not on the test classpath, and a fake chain pins the contract more directly.
     */
    private class FakeChain(
        private val request: Request,
        private val code: Int = 200,
        private val headers: Headers = Headers.headersOf(),
        /** Runs between signing and the response, so a test can rotate the credential in flight. */
        private val onProceed: () -> Unit = {},
        /**
         * The request the response reports as its own. Differs from the one we sent exactly when
         * OkHttp followed a redirect, which is what makes `response.request.url` the only URL that
         * can be trusted to have produced the response headers.
         */
        private val respondingRequest: Request? = null
    ) : Interceptor.Chain {

        var proceeded: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = request
            onProceed()
            return Response.Builder()
                .request(respondingRequest ?: request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .headers(headers)
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }

        override fun connection() = null
        override fun call(): Call = mockk(relaxed = true)
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun tileUrl(query: String = "") =
        "https://$gateway/v2/tiles/streets/1/2/3.pbf$query"

    private fun rollover(
        sid: String = "S2",
        exp: Long = now() + 7200,
        ends: Long = now() + 86400
    ): Headers = Headers.headersOf(
        "X-Map-Session-Id", sid,
        "X-Map-Session-Sig", "SIG2",
        "X-Map-Session-Exp", exp.toString(),
        "X-Map-Session-Ends", ends.toString(),
        "X-Map-Session-Key-Id", "2"
    )

    // ---------------------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------------------

    @Test
    fun tileRequestIsSigned() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val chain = FakeChain(request(tileUrl()))
        interceptor.intercept(chain)

        val sent: HttpUrl = chain.proceeded!!.url
        assertEquals("acct-1", sent.queryParameter("u"))
        assertEquals("S1", sent.queryParameter("s"))
        assertEquals("SIG1", sent.queryParameter("sig"))
    }

    @Test
    fun nonTileRequestIsUntouched() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val original = request("https://$gateway/styles/basic.json")
        val chain = FakeChain(original)
        interceptor.intercept(chain)

        // The same Request instance, not merely an equal URL: the interceptor must not rebuild a
        // request it has nothing to add to, or it would drop tags and headers set upstream.
        assertSame(original, chain.proceeded)
        assertNull(chain.proceeded!!.url.queryParameter("sig"))
    }

    // ---------------------------------------------------------------------------------
    // Rollover adoption
    // ---------------------------------------------------------------------------------

    @Test
    fun rolloverHeadersAreAdopted() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val chain = FakeChain(request(tileUrl()), code = 200, headers = rollover())
        interceptor.intercept(chain)

        // The new credential is now what gets signed onto the next tile.
        val next = MMMapSession.signedUrl(tileUrl().toHttpUrl())
        assertEquals("S2", next.queryParameter("s"))
        assertEquals("SIG2", next.queryParameter("sig"))
    }

    @Test
    fun rolloverFromAForeignHostIsRefused() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val chain = FakeChain(
            request("https://evil.example.com/v2/tiles/streets/1/2/3.pbf"),
            code = 200,
            headers = rollover()
        )
        interceptor.intercept(chain)

        val next = MMMapSession.signedUrl(tileUrl().toHttpUrl())
        assertEquals("a foreign host must not own our credential", "S1", next.queryParameter("s"))
    }

    /**
     * This is an APPLICATION interceptor, so `chain.request()` is the pre-redirect URL while
     * `response.request.url` is the one that actually answered. Adopting against the former would
     * let a cross-host redirect target's headers pass an origin check they never satisfied.
     */
    @Test
    fun rolloverFromARedirectTargetOffOriginIsRefused() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed()
        val chain = FakeChain(
            request(tileUrl()),
            code = 200,
            headers = rollover(),
            respondingRequest = request("https://evil.example.com/v2/tiles/streets/1/2/3.pbf")
        )
        interceptor.intercept(chain)

        val next = MMMapSession.signedUrl(tileUrl().toHttpUrl())
        assertEquals(
            "the host that actually answered, not the one we asked, decides adoption",
            "S1",
            next.queryParameter("s")
        )
    }

    // ---------------------------------------------------------------------------------
    // The SHIPPED path: no manifest meta-data, so the origin is learned from traffic
    // ---------------------------------------------------------------------------------

    @Test
    fun unpinnedInterceptorLearnsTheOriginAndSignsTheNextTile() {
        // No pinConfiguredOrigin: this is what an app without the meta-data actually does.
        MMMapSession.seedAccountForTesting("acct-1")
        val first = FakeChain(request(tileUrl()))
        interceptor.intercept(first)
        // Nothing to sign with yet, but the origin is now known.
        assertNull(first.proceeded!!.url.queryParameter("sig"))
        assertEquals(gateway, MMMapSession.originForTesting()!!.host)

        seed()
        val second = FakeChain(request(tileUrl()))
        interceptor.intercept(second)
        assertEquals("S1", second.proceeded!!.url.queryParameter("s"))
    }

    @Test
    fun aSecondHostCannotRePointALearnedOrigin() {
        MMMapSession.seedAccountForTesting("acct-1")
        interceptor.intercept(FakeChain(request(tileUrl())))
        assertEquals(gateway, MMMapSession.originForTesting()!!.host)

        // A style document naming another tile host must not move the origin: refreshNow POSTs
        // the permanent API key there.
        interceptor.intercept(
            FakeChain(request("https://evil.example.com/v2/tiles/streets/1/2/3.pbf"))
        )
        assertEquals(
            "the origin is learned once and never re-pointed",
            gateway,
            MMMapSession.originForTesting()!!.host
        )
    }

    // ---------------------------------------------------------------------------------
    // 401 recovery
    // ---------------------------------------------------------------------------------

    @Test
    fun matchingSession401TriggersRefresh() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")
        val before = MMMapSession.refreshCallCountForTesting()
        val chain = FakeChain(request(tileUrl()), code = 401)
        interceptor.intercept(chain)

        assertEquals("S1", chain.proceeded!!.url.queryParameter("s"))
        assertEquals(before + 1, MMMapSession.refreshCallCountForTesting())
    }

    @Test
    fun staleSession401DoesNotTriggerRefresh() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")
        val before = MMMapSession.refreshCallCountForTesting()
        // The real race: this tile went out signed with S1, and the credential rolled over to S9
        // before its 401 came back. The response is stale news about a credential we have already
        // replaced; buying another window for it would bill once per straggler after a
        // signing-key rotation.
        val chain = FakeChain(
            request(tileUrl()),
            code = 401,
            onProceed = { seed(session = "S9") }
        )
        interceptor.intercept(chain)

        assertEquals("S1", chain.proceeded!!.url.queryParameter("s"))
        assertEquals(before, MMMapSession.refreshCallCountForTesting())
    }

    /**
     * THE FALL-THROUGH. A 401 that also carries rollover headers we then REFUSE must still
     * trigger recovery. Written as `else if`, the header branch swallows the 401 and the session
     * machine is left with no usable credential and nothing scheduled — a blank map forever.
     */
    @Test
    fun refused401RolloverHeadersStillTriggerRefresh() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")
        val before = MMMapSession.refreshCallCountForTesting()

        // Refused because it is not newer than what we hold: exp is in the past.
        val stale = rollover(sid = "S2", exp = now() - 10, ends = now() - 10)
        val chain = FakeChain(request(tileUrl("?s=S1")), code = 401, headers = stale)
        interceptor.intercept(chain)

        assertEquals(
            "a refused rollover on a 401 must fall through to recovery",
            before + 1,
            MMMapSession.refreshCallCountForTesting()
        )
    }

    @Test
    fun adopted200DoesNotTriggerRefresh() {
        MMMapSession.pinConfiguredOrigin("https://$gateway")
        seed(session = "S1")
        val before = MMMapSession.refreshCallCountForTesting()
        val chain = FakeChain(request(tileUrl("?s=S1")), code = 200, headers = rollover())
        interceptor.intercept(chain)

        assertEquals(before, MMMapSession.refreshCallCountForTesting())
    }

    // ---------------------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------------------

    /**
     * THE HIGHEST-RISK LINE. Built from OkHttpClient.Builder() instead of the default client's
     * newBuilder(), this passes every other test and silently regresses v1 billing ~200x.
     */
    @Test
    fun installedClientKeepsTheCookieJar() {
        MMMapSessionInterceptor.install()
        val default = MMHttpClients.defaultClient()
        val installed = MMMapSessionInterceptor.installedClientForTesting()!!

        assertSame(
            "the installed client must reuse the default client's cookie jar, or v1 billing " +
                "regresses ~200x with no other symptom",
            default.cookieJar,
            installed.cookieJar
        )
        assertSame(default.dispatcher, installed.dispatcher)
        assertTrue(installed.interceptors.any { it is MMMapSessionInterceptor })
    }

    // ---------------------------------------------------------------------------------
    // Configured origin (invariant 3) and key rotation
    // ---------------------------------------------------------------------------------

    @Test
    fun installPinsTheOriginFromTheManifestMetaData() {
        setGatewayMetaData("https://pinned.example.com")
        MMMapSessionInterceptor.install(RuntimeEnvironment.getApplication())

        assertEquals(
            "the manifest, not traffic, must decide where the API key is POSTed",
            "pinned.example.com",
            MMMapSession.originForTesting()!!.host
        )

        // And a pinned origin refuses to sign for anybody else, however many tiles arrive.
        seed()
        val chain = FakeChain(request(tileUrl()))
        interceptor.intercept(chain)
        assertNull(chain.proceeded!!.url.queryParameter("sig"))
    }

    @Test
    fun installWithoutMetaDataLeavesTheOriginToBeLearned() {
        setGatewayMetaData(null)
        MMMapSessionInterceptor.install(RuntimeEnvironment.getApplication())

        assertNull(
            "an absent meta-data must not pin anything, or the fallback path breaks",
            MMMapSession.originForTesting()
        )
    }

    @Test
    fun setApiKeyUpdatesTheSessionCache() {
        MMMapSession.cacheApiKey("old-key")
        try {
            MapLibre.setApiKey("new-key")
        } catch (throwable: Throwable) {
            // FileSource.getInstance loads the native libs, which unit tests do not have. The
            // cache update deliberately happens BEFORE that call, so it has already run.
        }

        assertEquals(
            "a rotated key must reach the session layer, or every create sends a dead token",
            "new-key",
            MMMapSession.cachedApiKeyForTesting()
        )
    }

    // ---------------------------------------------------------------------------------
    // Foreground recovery — the only escape from a permanently blank map
    // ---------------------------------------------------------------------------------

    /** Drives the give-up state the way three spaced hard failures would. */
    private fun giveUp() {
        repeat(MMMapSession.MAX_CONSECUTIVE_HARD_FAILURES) {
            MMMapSession.handleRefreshFailure(401)
            MMMapSession.rewindFailureClocksForTesting(
                MMMapSession.MIN_HARD_FAILURE_SPACING_SECONDS + 1
            )
        }
        assertTrue("precondition: the session must have given up", MMMapSession.hasGivenUpForTesting())
    }

    @Test
    fun enteringTheForegroundClearsTheGiveUpState() {
        giveUp()
        MMMapSessionInterceptor.notifyActivityStartedForTesting()

        assertFalse(
            "without this the app is blank until the process restarts",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    @Test
    fun navigatingBetweenActivitiesDoesNotClearTheGiveUpState() {
        MMMapSessionInterceptor.notifyActivityStartedForTesting() // A starts: foreground
        giveUp()
        // A -> B: B starts before A stops, so the count never returns to zero.
        MMMapSessionInterceptor.notifyActivityStartedForTesting()
        MMMapSessionInterceptor.notifyActivityStoppedForTesting()

        assertTrue(
            "plain navigation is not a foreground entry",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    /**
     * THE ROTATION GUARD. A configuration change runs stop -> destroy -> create -> start. A naive
     * counter goes 1 -> 0 -> 1 and fires the foreground edge, so a user rotating the device
     * repeatedly would clear gaveUp/hardFailures every time and defeat the three-failure guard,
     * hammering a key the gateway will never accept.
     */
    @Test
    fun rotatingTheDeviceDoesNotClearTheGiveUpState() {
        MMMapSessionInterceptor.notifyActivityStartedForTesting()
        giveUp()
        MMMapSessionInterceptor.notifyActivityStoppedForTesting(isChangingConfigurations = true)
        MMMapSessionInterceptor.notifyActivityStartedForTesting()

        assertTrue(
            "a rotation must not re-arm the refresh loop",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    @Test
    fun aRealBackgroundReturnAfterARotationStillRecovers() {
        MMMapSessionInterceptor.notifyActivityStartedForTesting()
        MMMapSessionInterceptor.notifyActivityStoppedForTesting(isChangingConfigurations = true)
        MMMapSessionInterceptor.notifyActivityStartedForTesting()
        // If the rotation guard leaked a count, the app would never look backgrounded again and
        // recovery would be dead for the life of the process.
        MMMapSessionInterceptor.notifyActivityStoppedForTesting()
        giveUp()
        MMMapSessionInterceptor.notifyActivityStartedForTesting()

        assertFalse(
            "the rotation guard must not leave the counter drifted",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    @Test
    fun installIsIdempotent() {
        MMMapSessionInterceptor.install()
        val first = MMMapSessionInterceptor.installedClientForTesting()!!
        MMMapSessionInterceptor.install()
        MMMapSessionInterceptor.install()
        val last = MMMapSessionInterceptor.installedClientForTesting()!!

        assertSame("install() must not rebuild the client", first, last)
        // Stacking a second interceptor would drive N adoptions and N refreshes per response.
        assertEquals(1, last.interceptors.count { it is MMMapSessionInterceptor })
        // And the default client itself must never be mutated.
        assertEquals(
            0,
            MMHttpClients.defaultClient().interceptors.count { it is MMMapSessionInterceptor }
        )
    }
}
