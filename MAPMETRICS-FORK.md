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

## Verification — REQUIRED, do not skip

A re-vendor that drops any of the above still COMPILES. Silence is not success.
    make run-android-unit-test
and confirm the v2 session tests are present and passing.
