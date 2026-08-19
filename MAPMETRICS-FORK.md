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
3. **v2 map-session install hook** in `platform/android/.../MapLibre.java`.
   Two lines in EACH `getInstance` overload, after the API key is known and before `return`:
   `MMMapSession.cacheApiKey(apiKey); MMMapSessionInterceptor.install(INSTANCE.context);`
   plus the two imports. Search for "MAPMETRICS PATCH -- v2 map sessions".
   Without it nothing signs tiles: v2 requests 401 forever and the map is blank.
   The API key MUST be captured here — `MapLibre.getApiKey()` goes through
   `ThreadUtils.checkThread` and throws off the UI thread, and refresh runs on OkHttp
   dispatcher threads. Everything else v2 lives in new files
   (`org/maplibre/android/session/*`, `module/http/MMHttpClients.java`) and replays for free.
4. **Branding**: `maplibre_mapmetrics_map_logo` in `MapView.java` and `MapSnapshotter.kt`,
   the PNG, ~25 `res/values-*/strings.xml`, and `res-public/values/public.xml`.
5. **Build/publishing**: `build.gradle.kts`, `gradle.properties`.

## Verification — REQUIRED, do not skip

A re-vendor that drops any of the above still COMPILES. Silence is not success.
    make run-android-unit-test
and confirm the v2 session tests are present and passing.
