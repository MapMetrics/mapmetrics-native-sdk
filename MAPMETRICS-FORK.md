# MapMetrics fork of MapLibre Native (Android)

Upstream baseline: **`ios-v6.28.0`** = `18545ca10d296704b2cba675b182ff2c8da24588` (2026-07-23),
merged 2026-08. This tag is chosen deliberately, not for being newest: the sibling iOS fork
(`Native-MapMetrics-iOS-SDK`) was snapshotted from exactly it, so pinning here makes the two trees
CONVERGE. After the merge `platform/darwin`, `platform/ios`, `platform/macos`, `src/` and
`include/` are byte-identical to the tag. Do not move to a newer tag without moving iOS too --
the convergence is the point.

Previous fork point (pre-upgrade, for archaeology): dfa80771346ffba43999aa890578e796c73bd755
(2025-04-22).
Reproduce the divergence with:
    git remote add upstream https://github.com/maplibre/maplibre-native.git
    git fetch upstream --tags --filter=blob:none
    git diff --name-only ios-v6.28.0..HEAD

## Patches to replay on every re-vendor

1. **InMemoryCookieJar** in `platform/android/.../module/http/HttpRequestImpl.java`.
   Upstream has no cookie jar. It holds the gateway's `usageSession` cookie, which is
   what makes v1 bill once per 30-minute window instead of once per tile.
   DROPPING THIS REGRESSES BILLING ~200x, SILENTLY. Tiles keep working; only the invoice changes.

   The wiring point moved at the ios-v6.28.0 upgrade. Upstream replaced the eager
   `static final DEFAULT_CLIENT` with a lazily built `static volatile defaultClient`, created in
   `getOrCreateDefaultClient()`. The jar now goes on the builder INSIDE that method, and the
   method is package-private (not `private`, as upstream has it) so `MMHttpClients` can hand the
   same instance to the v2 signing client. Keep the laziness: upstream's `HttpRequestUtilTest`
   asserts `defaultClient` is still null before first use, so restoring the old eager field
   turns that test red. Search for "MAPMETRICS PATCH -- InMemoryCookieJar".
2. **401/403 -> Error::Reason::Server** in `platform/android/.../src/cpp/http_file_source.cpp`.
   Upstream maps them to Reason::Other, which the shared http_timeout.cpp never retries.
   Search for "MAPMETRICS PATCH -- v2 map sessions".
   SCOPED TO `resource.kind == Resource::Kind::Tile`, and the scoping is part of the patch. Applied
   unconditionally, the branch swallows EVERY 401/403 the SDK sees — styles, sprites, glyphs, v1
   tiles, third-party hosts — and retries each with unbounded backoff instead of surfacing a
   terminal error. An app with a revoked or unscoped key then shows a permanently blank map with
   NO error delivered to `onDidFailLoadingMap`, which is the same silent failure this branch exists
   to remove. Only tiles take part in the v2 session recovery loop, so only tiles need the retry.
   TRADE-OFF: a 401 on the STYLE document of a v2-session app is terminal again. That is deliberate
   — retrying cannot fix it, and an app can only react to an error it is told about.
   No test EXECUTES this code — the unit suite runs with `-Pmaplibre.abis=none` and never builds
   the native library. It is guarded at source level instead, by
   `MMMapSessionIntegrationTest.nativeHttpSourceStillTreats401And403AsRetryable`, which reads this
   file and asserts the branch still maps to `Reason::Server`. That guard depends on the **Test-task input declaration** patch below.
3. **v2 map-session install hook** in `platform/android/.../MapLibre.java`, THREE sites.
   Search for "MAPMETRICS PATCH -- v2 map sessions". Replay each exactly:
   - `getInstance(Context)` — before `return INSTANCE;`:
     `MMMapSession.cacheApiKey(null); MMMapSessionInterceptor.install(INSTANCE.context);`
     Note the literal `null`: this overload deliberately clears the key.
   - `getInstance(Context, String apiKey, WellKnownTileServer)` — before `return INSTANCE;`:
     `MMMapSession.cacheApiKey(apiKey); MMMapSessionInterceptor.install(INSTANCE.context);`
   - `setApiKey(String apiKey)` — after `INSTANCE.apiKey = apiKey;`:
     `MMMapSession.cacheApiKey(apiKey);`
     Miss this one and a key rotated at runtime leaves the session layer sending the old
     token: every create 401s until the give-up guard trips, and the map goes blank.
   Plus the two imports. Without the install hook nothing signs tiles: v2 requests 401 forever.
   The key is PUSHED to the session layer rather than pulled back out of `MapLibre` so the
   refresh path, which runs on OkHttp dispatcher threads, never touches `MapLibre`'s static
   state — `MapLibre` is `@UiThread` by contract. (`getApiKey()` is a plain field read and does
   not itself throw off the main thread; the point is not to depend on that.)
   Everything else v2 lives in new files (`org/maplibre/android/session/*`,
   `module/http/MMHttpClients.java`) and replays for free.

   NO RENEWAL TIMER — and a re-vendor must not reintroduce one. A credential is refreshed two
   ways and no other: ROLLOVER, where a tile carrying an expired-but-MAC-valid credential is
   served inline (HTTP 200, tile in the body) and the replacement arrives in `X-Map-Session-*`
   for `MMMapSession.applyCredentialFromHeaders`; and the spaced, budgeted 401 path via
   `refreshNow`, which is the way back from a credential the gateway REJECTS rather than one
   that merely lapsed. The gateway bills exactly 1 per window on rollover however many
   dead-credential tiles arrive at once. Rollover is demand-driven BY CONSTRUCTION — it fires
   only when a tile is actually requested — so "never bill an idle map" and "reopening the app
   must not bill" are facts about the shape of the system rather than gates that can be got
   wrong, and both had been got wrong at least once when they were gates. Anything that
   initiates a refresh on a clock, a lifecycle callback or a recorded-activity flag brings both
   bugs back. `MMMapSession.onEnterForeground` is kept but MUST NOT refresh: it exists for the
   give-up escape, and `MMMapSessionInterceptor` re-asserts the signing client on the same edge.
   Covered by `MMMapSessionTest.adoptingACredentialBuysNothing`, `signingATileBuysNothing`,
   `anIdleExpiredCredentialBillsNothing`,
   `aUsedExpiredCredentialStillBillsNothingUntilATileAsksForOne`, `foregroundingDoesNotBillAnIdleMap`,
   and `MMMapSessionInterceptorTest.foregroundingDoesNotBillAnIdleMap`.

   KNOWN LIMITATION — gateway origin. `MMMapSessionInterceptor` pins the origin from the host
   app's `AndroidManifest.xml` `<meta-data android:name="org.maplibre.android.MapSessionOrigin"
   android:value="https://..."/>`. When that meta-data is absent the origin is instead LEARNED
   from the first https TILE-SHAPED URL seen, and `refreshNow` later POSTs the customer's
   permanent API key to that host. Learning is https-only and one-shot, so no later response can
   re-point it, but it is weaker than configuration. `WellKnownTileServer` has no MapMetrics
   entry and the native `TileServerOptions` carry no gateway host, so there is no other
   configured source to pin from; adding one would remove the fallback's reason to exist.

   WHAT COUNTS AS A TILE — `MMMapSession.TILE_PATH_PATTERN`, the single shared definition, used by
   `signedUrl` and referenced (never re-spelt as a literal) by `MMMapSessionInterceptor`. It is a
   SHAPE, `/\d+/\d+/\d+\.mvt\z`, not a `/v2/tiles/` prefix. The gateway's universal tile endpoint
   accepts a v2 session signature on the EXISTING v1 tile path
   (`/{planet}/{z}/{x}/{y}.mvt?token=<JWT>`), so a new SDK signs the URL the style already gave it
   and the customer moves to session billing with no style change and no coordination; a prefix
   test would ignore every real style's tile URLs and the feature would never engage. This widens
   what could become the LEARNED origin above, which is why the origin rules must not be relaxed
   further. Covered by `MMMapSessionTest.aV1ShapedTileUrlIsSigned`,
   `theV2TilesFormIsStillSigned`, `aV1TokenQueryParamSurvivesSigning`,
   `nonTileShapedUrlsAreUntouchedAndTeachNoOrigin`,
   `aV1ShapedTileOnAForeignHostIsRefusedAndLoggedExactlyOnce`, their interceptor-level siblings,
   and end to end by the live `coldStartRecoversFrom401AndServesTilesOnOneCredential`.
4. **Branding**: `maplibre_mapmetrics_map_logo` in `MapView.java` and `MapSnapshotter.kt`,
   the PNG, ~25 `res/values-*/strings.xml`, and `res-public/values/public.xml`.
   NOT REPLAYABLE FROM THIS DOCUMENT, and NOTHING TURNS RED IF IT IS DROPPED. There is no marker
   comment to grep for and no test asserts the resource names. Recover the actual content with:

       git diff ios-v6.28.0..HEAD -- \
         platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapView.java \
         platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/snapshotter/MapSnapshotter.kt \
         'platform/android/MapLibreAndroid/src/main/res*'

   Symptom if dropped: the map renders with MapLibre branding instead of MapMetrics. Visual only.
5. **Build/publishing**. NOT REPLAYABLE FROM THIS DOCUMENT, and NOTHING TURNS RED IF IT IS
   DROPPED — a wrong group id or version publishes cleanly. The files are:

   - `platform/android/buildSrc/src/main/kotlin/maplibre.artifact-settings.gradle.kts` — **this is
     where the group id and artifact id live** (`org.mapmetrics.android-sdk` /
     `mapmetrics-native-sdk`) plus the MapMetrics title, developer and SCM fields. Before the
     ios-v6.28.0 upgrade this document did not mention the file at all, which meant the one
     patch it warns "publishes cleanly when wrong" had no recorded home.
   - `platform/android/VERSION` — the artifact version, `1.0.1`. **The home for this moved at the
     ios-v6.28.0 upgrade.** It used to be `VERSION_NAME` at the top of
     `platform/android/MapLibreAndroid/gradle.properties` (this document previously named
     `platform/android/gradle.properties`, which was simply the wrong path). Upstream now reads
     the version from a root `VERSION` file in `maplibre.artifact-settings.gradle.kts`.
   - `platform/android/MapLibreAndroid/build.gradle.kts` — the dokka `sourceLink.remoteUrl`, and
     a legacy `publishing { }` block near the bottom pointing at GitHub Packages.

   Recover the actual content with:

       git diff ios-v6.28.0..HEAD -- \
         platform/android/buildSrc/src/main/kotlin/maplibre.artifact-settings.gradle.kts \
         platform/android/VERSION \
         platform/android/MapLibreAndroid/build.gradle.kts

   NO LONGER PATCHES, as of the ios-v6.28.0 upgrade — do not replay them:
   `platform/android/build.gradle.kts` (upstream removed root-project publishing entirely; it now
   uses `com.vanniktech.maven.publish.base` applied per module) and
   `buildSrc/src/main/kotlin/maplibre.publish-root.gradle.kts` and
   `buildSrc/src/main/kotlin/maplibre.gradle-publish.gradle.kts` (upstream deleted the first and
   rewrote the second; both of this fork's versions are gone and upstream's are taken as-is).
   The deleted files had held plaintext Sonatype and GPG credentials.

   Note that the first of those files also carries the **Test-task input declaration** patch below,
   which is a separate block; do not treat one diff hunk as covering both.
6. **Test-task input declaration** in `platform/android/MapLibreAndroid/build.gradle.kts`
   (search for "MAPMETRICS PATCH -- v2 map sessions" — it is a separate block from the
   **Build/publishing** patch's config, in the same file, and replaying that patch will NOT bring
   it along).
   Declares `src/cpp/http_file_source.cpp` as an input to the unit-test tasks so that the guard on
   patch 2 actually RE-RUNS when that file changes. A `.cpp` is not otherwise an input to a JVM
   test task: Gradle holds the test UP-TO-DATE and replays the previous result.
   DROP THIS AND PATCH 2 LOSES ITS ONLY PROTECTION, WITHOUT ANY TEST TURNING RED. You delete the
   401/403 branch, run the suite, and it passes — because the guard never ran. Verified: with this
   block removed, deleting the branch entirely still reports green.

## Verification — REQUIRED, do not skip

A re-vendor that drops any of the above still COMPILES. Silence is not success.

    cd platform/android && make run-android-unit-test

The renderer flavor was renamed at the ios-v6.28.0 upgrade: `drawable` became `opengl`, and the
Makefile's `RENDERER` now defaults to `opengl`. The task is `:MapLibreAndroid:testOpenglDebugUnitTest`;
`testDrawableDebugUnitTest` no longer exists. Post-upgrade the suite is 968 tests, 0 failures,
1 skipped (the live cold start, which needs `MM_STAGING_KEY`). Pre-upgrade it was 961/0/1.

(The `Makefile` lives in `platform/android/`, not at the repo root where the rest of this document
is rooted. Run it from the repo root and it fails with "No such file or directory".)

Confirm the v2 session tests are present and passing. Be precise about what that does and does not
prove — the suite is not uniformly strong across the patches above:

- Patches 1 and 3 are covered behaviourally by the unit suite.
- Patch 2 is covered only at SOURCE level, by
  `nativeHttpSourceStillTreats401And403AsRetryable`, because no JVM test can execute C++. It
  asserts the 401/403 branch still maps to `Reason::Server` and still carries the marker comment.
  It is only as good as the **Test-task input declaration** patch — see the warning there.
- Patches 4 (branding) and 5 (build/publishing) are NOT COVERED AT ALL. No test asserts a resource
  name, a group id or a version. A re-vendor that drops either reports fully green. Check them by
  eye against the `git diff` invocations given with each, every time.
- The live cold-start test, `coldStartRecoversFrom401AndServesTilesOnOneCredential`, SKIPS unless
  `MM_STAGING_KEY` is set, and a skipped JUnit test reports green. Running the suite offline
  therefore verifies LESS than it appears to. To exercise the real gateway path end to end:

      export MM_STAGING_KEY=...   # staging API key, from the environment only
      cd platform/android && ./gradlew -Pmaplibre.abis=none \
        :MapLibreAndroid:testOpenglDebugUnitTest

  Then confirm it RAN rather than skipped — check for `<skipped/>` in
  `MapLibreAndroid/build/test-results/testOpenglDebugUnitTest/TEST-*MMMapSessionIntegrationTest.xml`.
  A green run in which that test skipped tells you nothing about the gateway path.
