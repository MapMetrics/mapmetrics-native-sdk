package org.maplibre.android.session

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * End-to-end verification against the LIVE staging gateway.
 *
 * The rest of the suite proves the credential logic in isolation against fakes. This proves the
 * shipped path: a real OkHttp client carrying [MMMapSessionInterceptor] obtains a real credential
 * from the real gateway and uses it to fetch real tiles.
 *
 * WHY THIS TEST STARTS COLD, and why it must never be "simplified" by seeding a credential first:
 *
 * `MAPMETRICS-FORK.md` tells a future re-vendorer to confirm the v2 session tests pass as the check
 * that no MapMetrics patch was dropped. That guarantee has TWO halves, and this file covers them
 * with two different tests. Be precise about which is which — an earlier draft of this comment
 * claimed the cold start below would catch a reverted C++ patch, and that is FALSE.
 *
 * - The KOTLIN half — [coldStartRecoversFrom401AndServesTilesOnOneCredential]. It issues the FIRST
 *   tile with no credential held, so the gateway 401s, and asserts the recovery that hangs off it:
 *   the interceptor sees the 401, `shouldRefreshForResponseUrl` agrees it is ours, `refreshNow`
 *   buys a window, later tiles are signed and served. Break any of that and this test fails.
 *
 * - The NATIVE half — [nativeHttpSourceStillTreats401And403AsRetryable]. The 401/403 ->
 *   `Error::Reason::Server` mapping lives in C++, in `http_file_source.cpp`, and this suite runs
 *   with `-Pmaplibre.abis=none`: the native library is not compiled, never mind loaded, so NO test
 *   in this source set executes that code. The cold start above does not exercise it and would
 *   stay green with the patch reverted. That is why the second test exists and why it is a
 *   source-level check.
 *
 * Staging runs a COMPRESSED credential lifetime (ttl 20s, renewWindow 8s, grace 5s), so the whole
 * test is kept well inside one window: it asserts the one-credential-many-tiles property, not the
 * rollover path, and it captures the session id before the tile loop to prove no rollover happened
 * underneath it.
 *
 * Skips cleanly when `MM_STAGING_KEY` is absent so the suite stays runnable offline and in CI. The
 * key is read from the environment ONLY and is never written down here.
 */
@RunWith(RobolectricTestRunner::class)
class MMMapSessionIntegrationTest {

    private companion object {
        const val STAGING_BASE = "https://gateway-mapatlas-staging.jim9710.workers.dev"

        /** Distinct tiles, all real and all small. Kept short: the staging window is 20s. */
        val TILES = listOf(
            "6/33/22", "6/33/21", "6/32/22", "6/32/21",
            "5/16/11", "5/16/10", "4/8/5", "4/8/6"
        )

        const val CREDENTIAL_WAIT_MILLIS = 15_000L

        /** The string `MAPMETRICS-FORK.md` tells a re-vendorer to grep for. */
        const val PATCH_MARKER = "MAPMETRICS PATCH -- v2 map sessions"
    }

    private var apiKey: String? = null
    private lateinit var client: OkHttpClient

    /**
     * The native half of the same guarantee.
     *
     * The cold start below proves the KOTLIN recovery (interceptor sees a 401 -> `refreshNow` ->
     * signed tiles). It cannot prove the C++ half: this is a JVM unit test and the native library
     * is not even built here (`-Pmaplibre.abis=none`). But the C++ half is precisely what a
     * re-vendor drops — it is a hand-applied patch in a file that comes from upstream — and
     * without it `Reason::Other` makes 401 terminal, so the first tile of every cold start is
     * never retried and the map stays blank.
     *
     * So this asserts the patch is still THERE. It is a source-level check rather than a
     * behavioural one, which is weaker, but it is the strongest thing available from this test
     * source set and it fails loudly on exactly the event `MAPMETRICS-FORK.md` warns about.
     * Deliberately independent of `MM_STAGING_KEY`: it must run offline and in CI.
     */
    @Test
    fun nativeHttpSourceStillTreats401And403AsRetryable() {
        val source = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/cpp/http_file_source.cpp") }
            .firstOrNull { it.isFile }
            ?: generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .map {
                    java.io.File(
                        it,
                        "platform/android/MapLibreAndroid/src/cpp/http_file_source.cpp"
                    )
                }
                .firstOrNull { it.isFile }

        assertNotNull("could not locate http_file_source.cpp from ${java.io.File("").absolutePath}", source)
        val raw = source!!.readText()

        // MAPMETRICS-FORK.md tells the re-vendorer to grep for this exact string, so it is the
        // most re-vendor-faithful signal there is: if it is gone, the patch went with it.
        assertTrue(
            "the \"$PATCH_MARKER\" marker is gone from http_file_source.cpp. MAPMETRICS-FORK.md " +
                "tells you to grep for it; if this file was just re-vendored from upstream, the " +
                "401/403 patch was dropped and must be re-applied.",
            raw.contains(PATCH_MARKER)
        )

        // Strip // comments BEFORE looking at the branch body. The patch's own comment explains
        // why Reason::Other would be wrong, so a naive "body must not contain Reason::Other"
        // would trip over the very comment that documents the fix.
        val code = raw.lineSequence()
            .joinToString(" ") { it.substringBefore("//") }
            .replace(Regex("\\s+"), " ")

        // Scope the assertions to the BRANCH, not the file. Checking "the file contains
        // Reason::Server" anywhere is worthless: the 5xx branch immediately above already
        // satisfies it, so the check would stay green through the one mutation that matters —
        // Server swapped for Other while the branch stays put.
        //
        // `[^{}]` keeps the condition from spanning another branch's body; the non-greedy body
        // stops at the first `}` followed by `else`, which is the branch close and not the `}`
        // inside `std::string{"HTTP status code "}`.
        val branch = Regex(
            """else\s*if\s*\(\s*([^{}]*?code\s*==\s*40[13][^{}]*?)\s*\)\s*\{(.*?)\}\s*else\b""",
            RegexOption.DOT_MATCHES_ALL
        ).find(code)

        // The regex anchors the branch close on `} else`, so a file whose final `else` arm was
        // removed would not match either — a different fault with the same symptom. Say which,
        // rather than reporting "no branch" for a branch that is plainly there.
        if (branch == null && Regex("""code\s*==\s*40[13]""").containsMatchIn(code)) {
            throw AssertionError(
                "http_file_source.cpp still mentions 401/403 but this guard could not parse the " +
                    "branch around it — most likely onResponse's if/else chain was restructured " +
                    "(the guard expects the 401/403 arm to be followed by a final `else`). " +
                    "CHECK BY HAND that 401 and 403 still map to Error::Reason::Server, then fix " +
                    "this regex. Do not delete this test."
            )
        }
        assertNotNull(
            "http_file_source.cpp has no `else if (... code == 401/403 ...)` branch at all. " +
                "Without it a 401 falls through to Error::Reason::Other, which " +
                "src/mbgl/util/http_timeout.cpp treats as terminal (Duration::max()), so the " +
                "first tile of every cold start is never retried and the map stays blank. " +
                "Re-apply the MAPMETRICS PATCH described in MAPMETRICS-FORK.md.",
            branch
        )
        val condition = branch!!.groupValues[1]
        val body = branch.groupValues[2]

        // Comment-stripping is line-based, so a `//`-bearing string literal in this file would
        // truncate the body and could turn the Reason::Server check below into a false RED (or,
        // worse, hide a Reason::Other that sat past the truncation point). No such literal exists
        // today; this makes the day one appears fail with the reason rather than a puzzle.
        assertTrue(
            "the parsed 401/403 branch body does not contain the `response.error` assignment " +
                "(body: \"$body\"). The body was probably truncated by comment-stripping — check " +
                "for a string literal containing \"//\" in http_file_source.cpp.",
            body.contains("response.error")
        )

        // Both codes, in either order and with or without parentheses — the branch is what
        // matters, not how someone chose to punctuate it.
        assertTrue(
            "the retryable branch no longer covers both 401 and 403 (condition: \"$condition\")",
            condition.contains("401") && condition.contains("403")
        )
        assertTrue(
            "the 401/403 branch no longer maps to Error::Reason::Server (body: \"$body\"). That " +
                "mapping is what makes the cold-start 401 retryable with backoff while " +
                "MMMapSession re-authenticates.",
            body.contains("Error::Reason::Server")
        )
        assertFalse(
            "the 401/403 branch maps to Error::Reason::Other (body: \"$body\"). Reason::Other is " +
                "TERMINAL — http_timeout.cpp returns Duration::max() for it — so 401 is " +
                "non-retryable again, the unsigned first tile of every cold start is never " +
                "retried, and the map stays blank with nothing surfaced to the app.",
            body.contains("Error::Reason::Other")
        )
    }

    @Before
    fun setUp() {
        // The ONLY source of the key. Read here but NOT asserted here: the source-level patch
        // check above must still run offline, so the skip lives in the network test itself.
        apiKey = System.getenv("MM_STAGING_KEY")

        // A genuinely cold start: no credential, no account, and no origin either — the origin is
        // LEARNED from the first tile URL, which is what an app with no manifest meta-data does.
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()

        // Sibling tests replace the call factory with a mock so nothing leaves the JVM. This test
        // is the one that DOES want real traffic, and `resetForTesting` deliberately does not
        // touch the factory, so set it explicitly rather than inheriting whatever ran before.
        MMMapSession.callFactory = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        // Renew at exp-5s rather than the production exp-60s. With a 20s staging ttl the default
        // lead is already past, which would leave renewal permanently due; a 5s lead keeps the
        // renewal timer parked beyond the end of this test so we measure one window only.
        MMMapSession.renewLeadTimeSeconds = 5

        MMMapSession.cacheApiKey(apiKey)

        // Real traffic goes through the INTERCEPTOR, not through hand-rolled calls to signedUrl.
        // Anything else would test the test rather than the path that ships.
        client = OkHttpClient.Builder()
            .addInterceptor(MMMapSessionInterceptor())
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        MMMapSession.resetForTesting()
        MMMapSession.resetOriginForTesting()
        MMMapSession.callFactory = OkHttpClient()
    }

    @Test
    fun coldStartRecoversFrom401AndServesTilesOnOneCredential() {
        assumeTrue(
            "MM_STAGING_KEY is not set; skipping the live staging integration test",
            !apiKey.isNullOrEmpty()
        )
        assertFalse(
            "precondition: the test must begin with no credential",
            MMMapSession.hasCredentialForTesting()
        )
        assertEquals(0, MMMapSession.secondsUntilExpiry)

        // --- 1. cold start: the first tile goes out UNSIGNED and the gateway refuses it ------
        val firstTile = tileUrl(TILES.first())
        val firstResponse = client.newCall(Request.Builder().url(firstTile).build()).execute()
        val firstCode = firstResponse.code
        val firstSentUrl = firstResponse.request.url
        firstResponse.close()

        assertEquals(
            "the first tile must be refused; it carried no credential",
            401,
            firstCode
        )
        assertFalse(
            "the first tile must have gone out unsigned",
            firstSentUrl.queryParameterNames.contains("sig")
        )

        // --- 2. the SDK recovers from that 401 by buying a window ---------------------------
        // This is the assertion the fork doc's guarantee rests on: if 401 were terminal again,
        // no credential would ever appear here.
        awaitCredential()

        assertTrue(
            "the 401 must have produced a live credential",
            MMMapSession.secondsUntilExpiry > 0
        )
        assertEquals(
            "exactly one window must have been bought; each one is a billed map load",
            1,
            MMMapSession.refreshCallCountForTesting()
        )
        assertEquals(
            "the origin must have been learned from the tile URL",
            firstTile.host,
            MMMapSession.originForTesting()?.host
        )

        // The session id has no seam of its own; the signed URL carries it as `s`. Reading it
        // through signedUrl is also how we prove the credential is actually usable for signing.
        val sessionIdBefore = signedSessionId()
        assertNotNull("a credential must sign a session id", sessionIdBefore)

        // --- 3. tiles are then served, all on that one credential ---------------------------
        var served = 0
        for (tile in TILES) {
            val response = client.newCall(Request.Builder().url(tileUrl(tile)).build()).execute()
            val code = response.code
            val sentUrl = response.request.url
            val bytes = response.body?.bytes()?.size ?: 0
            response.close()

            assertEquals("tile $tile should have been served", 200, code)
            assertTrue(
                "tile $tile must have been signed on the way out",
                sentUrl.queryParameterNames.contains("sig")
            )
            assertEquals(
                "tile $tile must have been signed with the credential we started the loop with",
                sessionIdBefore,
                sentUrl.queryParameter("s")
            )
            assertTrue("tile $tile came back empty ($bytes bytes)", bytes > 1000)
            served++
        }
        assertEquals(TILES.size, served)

        // --- 4. one credential, many tiles ---------------------------------------------------
        // Without this, a mid-test rollover would leave everything above green while quietly
        // measuring the rollover path instead of the property we came to assert.
        assertEquals(
            "the credential must not have rolled over mid-test",
            sessionIdBefore,
            signedSessionId()
        )
        assertEquals(
            "serving ${TILES.size} tiles must not have bought a second window",
            1,
            MMMapSession.refreshCallCountForTesting()
        )
        assertFalse(
            "the refresh loop must not have given up",
            MMMapSession.hasGivenUpForTesting()
        )
    }

    private fun tileUrl(tile: String): HttpUrl = "$STAGING_BASE/v2/tiles/$tile.mvt".toHttpUrl()

    /** The `s` parameter the SDK would sign onto a tile right now, or null if it cannot sign. */
    private fun signedSessionId(): String? =
        MMMapSession.signedUrl(tileUrl(TILES.first())).queryParameter("s")

    /** [MMMapSession.refreshNow] is asynchronous, so the credential arrives on another thread. */
    private fun awaitCredential() {
        val deadline = System.currentTimeMillis() + CREDENTIAL_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (MMMapSession.hasCredentialForTesting() && MMMapSession.secondsUntilExpiry > 0) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "no credential appeared within ${CREDENTIAL_WAIT_MILLIS}ms of the cold-start 401. " +
                "If 401 is no longer retryable / no longer triggers refreshNow, this is exactly " +
                "the regression this test exists to catch."
        )
    }
}
