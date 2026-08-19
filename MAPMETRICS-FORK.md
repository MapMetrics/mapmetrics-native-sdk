# MapMetrics fork of MapLibre Native (Android)

Fork point: dfa80771346ffba43999aa890578e796c73bd755 (2025-04-22).
Reproduce the divergence with:
    git remote add upstream https://github.com/maplibre/maplibre-native.git
    git fetch upstream --tags --filter=blob:none
    git diff --name-only $(git merge-base main upstream/main)..main

## Patches to replay on every re-vendor

1. **InMemoryCookieJar** in `platform/android/.../module/http/HttpRequestImpl.java`.
   Upstream has no cookie jar. It holds the gateway's `usageSession` cookie, which is
   what makes v1 bill once per 30-minute window instead of once per tile.
   DROPPING THIS REGRESSES BILLING ~200x, SILENTLY. Tiles keep working; only the invoice changes.
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

       git diff $(git merge-base main upstream/main)..main -- \
         platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapView.java \
         platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/snapshotter/MapSnapshotter.kt \
         'platform/android/MapLibreAndroid/src/main/res*'

   Symptom if dropped: the map renders with MapLibre branding instead of MapMetrics. Visual only.
5. **Build/publishing**: `build.gradle.kts`, `gradle.properties`.
   NOT REPLAYABLE FROM THIS DOCUMENT, and NOTHING TURNS RED IF IT IS DROPPED — a wrong group id or
   version publishes cleanly. Recover the actual content with:

       git diff $(git merge-base main upstream/main)..main -- \
         platform/android/MapLibreAndroid/build.gradle.kts \
         platform/android/gradle.properties

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
        :MapLibreAndroid:testDrawableDebugUnitTest

  Then confirm it RAN rather than skipped — check for `<skipped/>` in
  `MapLibreAndroid/build/test-results/testDrawableDebugUnitTest/TEST-*MMMapSessionIntegrationTest.xml`.
  A green run in which that test skipped tells you nothing about the gateway path.
