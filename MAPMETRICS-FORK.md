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
   No test EXECUTES this code — the unit suite runs with `-Pmaplibre.abis=none` and never builds
   the native library. It is guarded at source level instead, by
   `MMMapSessionIntegrationTest.nativeHttpSourceStillTreats401And403AsRetryable`, which reads this
   file and asserts the branch still maps to `Reason::Server`. That guard depends on item 6.
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
   from the first https `/v2/tiles/` URL seen, and `refreshNow` later POSTs the customer's
   permanent API key to that host. Learning is https-only and one-shot, so no later response can
   re-point it, but it is weaker than configuration. `WellKnownTileServer` has no MapMetrics
   entry and the native `TileServerOptions` carry no gateway host, so there is no other
   configured source to pin from; adding one would remove the fallback's reason to exist.
4. **Branding**: `maplibre_mapmetrics_map_logo` in `MapView.java` and `MapSnapshotter.kt`,
   the PNG, ~25 `res/values-*/strings.xml`, and `res-public/values/public.xml`.
5. **Build/publishing**: `build.gradle.kts`, `gradle.properties`.
6. **Test-task input declaration** in `platform/android/MapLibreAndroid/build.gradle.kts`
   (search for "MAPMETRICS PATCH -- v2 map sessions" — it is a separate block from item 5's
   publishing config, in the same file, and replaying item 5 will NOT bring it along).
   Declares `src/cpp/http_file_source.cpp` as an input to the unit-test tasks so that the guard on
   patch 2 actually RE-RUNS when that file changes. A `.cpp` is not otherwise an input to a JVM
   test task: Gradle holds the test UP-TO-DATE and replays the previous result.
   DROP THIS AND PATCH 2 LOSES ITS ONLY PROTECTION, WITHOUT ANY TEST TURNING RED. You delete the
   401/403 branch, run the suite, and it passes — because the guard never ran. Verified: with this
   block removed, deleting the branch entirely still reports green.

## Verification — REQUIRED, do not skip

A re-vendor that drops any of the above still COMPILES. Silence is not success.

    make run-android-unit-test

Confirm the v2 session tests are present and passing. Be precise about what that does and does not
prove — the suite is not uniformly strong across the patches above:

- Patches 1 and 3 are covered behaviourally by the unit suite.
- Patch 2 is covered only at SOURCE level, by
  `nativeHttpSourceStillTreats401And403AsRetryable`, because no JVM test can execute C++. It
  asserts the 401/403 branch still maps to `Reason::Server` and still carries the marker comment.
  It is only as good as item 6 — see the warning there.
- The live cold-start test, `coldStartRecoversFrom401AndServesTilesOnOneCredential`, SKIPS unless
  `MM_STAGING_KEY` is set, and a skipped JUnit test reports green. Running the suite offline
  therefore verifies LESS than it appears to. To exercise the real gateway path end to end:

      export MM_STAGING_KEY=...   # staging API key, from the environment only
      cd platform/android && ./gradlew -Pmaplibre.abis=none \
        :MapLibreAndroid:testDrawableDebugUnitTest

  Then confirm it RAN rather than skipped — check for `<skipped/>` in
  `MapLibreAndroid/build/test-results/testDrawableDebugUnitTest/TEST-*MMMapSessionIntegrationTest.xml`.
  A green run in which that test skipped tells you nothing about the gateway path.
