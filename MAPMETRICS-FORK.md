# MapMetrics fork of MapLibre Native (Android + iOS/darwin)

**This repository now carries BOTH platforms.** The former `Native-MapMetrics-iOS-SDK` repo is
folded in here; it is no longer the home of the iOS work. Its patch set lives in
*Patches to replay on every re-vendor — iOS / darwin* below.

Upstream baseline: **`ios-v6.28.0`** = `18545ca10d296704b2cba675b182ff2c8da24588` (2026-07-23),
merged 2026-08. This tag is chosen deliberately, not for being newest: the sibling iOS fork
(`Native-MapMetrics-iOS-SDK`) was snapshotted from exactly it, so pinning here made the two trees
CONVERGE — which is what allowed the iOS work to be ported as a clean replay of a small patch set
rather than a merge (the two repos share no git history at all, so `git merge` was never possible).
Before the iOS port `platform/darwin`, `platform/ios`, `platform/macos`, `src/` and `include/` were
byte-identical to the tag; they now differ ONLY by the iOS patches listed below. Do not move to a
newer tag for one platform alone -- the convergence is the point.

**The rebrand is deliberately MINIMAL here and must stay that way.** The old iOS repo ran a full
`maplibre`->`mapmetrics` rename over file CONTENTS *and* PATHS. That rename broke its own Android
build in three compounding layers: stale Gradle project references, references living outside
`platform/android` that a scoped grep misses, and 11 buildSrc convention plugins whose FILENAME IS
THEIR PLUGIN ID -- so 11 plugin ids moved silently while 17 `id("maplibre.…")` usages still asked
for the old ones. This repo renames branding only (logo + strings); `org.maplibre` packages,
`MLN*` type names and every upstream path keep their upstream spelling. Preserving that minimalism
is worth more than naming consistency. When porting anything else from the old iOS repo, adapt it
to the naming HERE rather than renaming anything here to match it.

Previous fork point (pre-upgrade, for archaeology): dfa80771346ffba43999aa890578e796c73bd755
(2025-04-22).
Reproduce the divergence with:
    git remote add upstream https://github.com/maplibre/maplibre-native.git
    git fetch upstream --tags --filter=blob:none
    git diff --name-only ios-v6.28.0..HEAD

## Patches to replay on every re-vendor — Android

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

## Patches to replay on every re-vendor — iOS / darwin

The iOS side carries almost no original code. Apart from branding, the MapMetrics-specific
behaviour is just two things: the v2 map-session credential (`platform/darwin/src/MMMapSession*`
plus the 401/403 retry patch) and `-[MLNMapView initWithFrame:isDarkMode:]`. So an iOS upgrade is
NOT a merge either — it is a re-vendor of upstream plus a replay of this short list.

A. **v2 map-session sources** — five NEW files, no upstream counterpart, nothing to merge:

       platform/darwin/src/MMMapSession.h
       platform/darwin/src/MMMapSession.mm
       platform/darwin/src/MMMapSession_Private.h
       platform/darwin/src/MMMapSessionNetworkDelegate.h
       platform/darwin/src/MMMapSessionNetworkDelegate.mm

   Their tests are `platform/darwin/test/MMMapSessionTests.m` (45 cases) and
   `platform/darwin/test/MMMapSessionIntegrationTests.m` (live, gated on `MM_STAGING_KEY`).
   The tests need no registration — `platform/darwin/BUILD.bazel` globs `test/*.m`. THE SOURCES DO.
   See patch B, which is the one that fails silently.

   NO RENEWAL TIMER, and a re-vendor must not reintroduce one. `MMMapSession` refreshes its
   credential two ways and no other: ROLLOVER, where a tile carrying an expired-but-MAC-valid
   credential is served inline (HTTP 200, tile in the body) and the replacement arrives in
   `X-Map-Session-*` for `-applyCredentialFromHeaders:responseURL:`; and the spaced, budgeted
   401/403 path via `-refreshNow`, the way back from a credential the gateway REJECTS rather than
   one that merely lapsed. The gateway bills exactly 1 per window on rollover however many
   dead-credential tiles arrive at once. Rollover is demand-driven BY CONSTRUCTION — it fires only
   when a tile is actually requested — so "never bill an idle map" (a phone on a desk once billed
   ~16 windows overnight) and "reopening the app must not bill a map that is not on screen" are
   facts about the SHAPE of the system rather than gates that can be got wrong. Anything that
   initiates a refresh on a timer, a lifecycle notification or a recorded-activity flag brings both
   bugs back. `-applicationWillEnterForeground` is kept but MUST NOT refresh: it re-asserts the
   network delegate that `MLNNetworkConfiguration` holds WEAKLY, and is the fast escape from the
   give-up state. Covered by `MMMapSessionTests.testAdoptingACredentialBuysNothing`,
   `testSigningATileBuysNothing`, `testIdleMapBuysNothing`,
   `testAnExpiredCredentialDoesNotBuyAWindow` and `testForegroundEntryBuysNothing`.

       grep -c 'NSTimer\|scheduledTimer' platform/darwin/src/MMMapSession.mm   # expect 0

B. **Source registration** in `platform/darwin/bazel/files.bzl` — 5 entries.
   THIS IS THE ONE THAT FAILS SILENTLY, and it has bitten this codebase twice.
   `platform/darwin/src/` is an EXPLICIT LIST, NOT A GLOB. Drop these entries and the SDK still
   compiles and links **cleanly, with no session code in it at all** — no error, no warning — and
   every tile then ships unsigned, every one of them bills, and nothing anywhere says so.

   | List | Entries |
   |---|---|
   | `MLN_DARWIN_OBJC_HEADERS` | `"src/MMMapSession.h"`, `"src/MMMapSessionNetworkDelegate.h"` |
   | `MLN_DARWIN_PRIVATE_HEADERS` | `"src/MMMapSession_Private.h"` |
   | `MLN_DARWIN_PUBLIC_OBJCPP_SOURCE` | `"src/MMMapSession.mm"`, `"src/MMMapSessionNetworkDelegate.mm"` |

       grep -c MMMapSession platform/darwin/bazel/files.bzl   # expect 5

   A GREEN BUILD DOES NOT PROVE THIS. Prove it against the build graph and the ARTIFACT:

       bazel query 'labels(srcs, //platform/darwin:darwin_objcpp_srcs)' | grep MMMapSession
       bazel query 'labels(srcs, //platform/darwin:darwin_objc_hdrs)'   | grep MMMapSession
       bazel query 'labels(srcs, //platform/darwin:darwin_private_hdrs)' | grep MMMapSession
       # then, after building MapLibre.dynamic:
       unzip -o bazel-bin/platform/ios/MapLibre.dynamic.xcframework.zip -d /tmp/xcf
       nm /tmp/xcf/MapLibre.xcframework/ios-arm64/MapLibre.framework/MapLibre \
         | grep '_OBJC_CLASS_\$_MMMapSession'      # expect 2 classes

C. **401/403 -> `Error::Reason::Server`** in `platform/darwin/core/http_file_source.mm`.
   Search for "MAPMETRICS PATCH -- v2 map sessions". Upstream maps unhandled status codes to
   `Error::Reason::Other`, which the shared `http_timeout.cpp` treats as permanently
   non-retryable (`Duration::max()`), so `OnlineFileRequest::schedule` never retries and a 401
   tile stays blank forever with no error surfaced. Two cases produce a 401 and BOTH are
   recoverable: the very first request, sent before `MMMapSession` has a credential
   (`willSendRequest:` is synchronous and cannot await the create call); and a signing-key
   rotation, which invalidates every live credential at once. `Reason::Server` gets exponential
   backoff, which is what we want while `MMMapSession` re-authenticates.

       grep -c 'Reason::Server' platform/darwin/core/http_file_source.mm   # expect >= 1

   NOTE the asymmetry with Android patch 2, and do not "fix" it: the Android branch is SCOPED to
   `Resource::Kind::Tile`, this darwin one is not. Unscoped is the older, broader form. If this
   ever needs narrowing, read Android patch 2 first — it explains the trade-off in full.
   This patch at least fails LOUDLY (blank tiles), unlike B and D.

D. **`env_inherit`** in `platform/ios/test/BUILD.bazel`, inside `ios_unit_test(name = "ios_test")`:

       env_inherit = ["MM_STAGING_KEY"],

   FAILS SILENTLY, and in the worse direction than B: without it the staging key never reaches the
   simulator process, so `MMMapSessionIntegrationTests` `XCTSkip`s and THE SUITE STILL REPORTS
   GREEN. The end-to-end gate stops gating. `--test_env` alone does **not** substitute for it under
   `rules_apple`'s `ios_unit_test`.

       grep -c MM_STAGING_KEY platform/ios/test/BUILD.bazel   # expect 1

   A green suite is not proof. Prove the key actually ARRIVES — run with it set and confirm the
   skip message is gone, not merely that the suite passed:

       MM_STAGING_KEY=... bazel test //platform/ios/test:ios_test \
         --//:renderer=metal --apple_platform_type=ios --ios_minimum_os=12.0 \
         --test_filter=MMMapSessionIntegrationTests --nocache_test_results
       grep -c 'set MM_STAGING_KEY to run' \
         "$(bazel info bazel-testlogs)/platform/ios/test/ios_test/test.log"   # expect 0

E. **`-[MLNMapView initWithFrame:isDarkMode:]`** in `platform/ios/src/MLNMapView.{h,mm}`.
   Loads the light or dark style the host app configures. Nothing about the endpoints is compiled
   into the SDK — an earlier revision hardcoded a **non-expiring JWT** and two style URLs directly
   in `MLNMapView.mm`, readable by anyone with the binary or the repo. Do not reintroduce
   credentials into source. The host supplies them via its `Info.plist`:

   | Key | Purpose |
   |---|---|
   | `MLNMapMetricsLightStyleURL` | style loaded by `initWithFrame:isDarkMode:NO` |
   | `MLNMapMetricsDarkStyleURL` | style loaded by `initWithFrame:isDarkMode:YES` |
   | `MLNApiKey` | appended as `token` when the style URL has no `token` query item |
   | `MLNTileServerBaseURL` | upstream key, overrides the tile server base URL. Also **pins** the v2 map-session origin: when set, `MMMapSession` posts the API key only to this host and never learns an origin from traffic. Set it in production. |

   Missing or malformed keys log a warning and fall back to the default style, so the map still
   renders. `isDarkMode` selects the *map style* and is independent of `UITraitCollection` — a dark
   basemap can be shown in light system appearance.

       grep -c 'isDarkMode' platform/ios/src/MLNMapView.h   # expect >= 1

   NOT COVERED BY ANY TEST. Nothing turns red if it is dropped.

F. **iOS branding — DELIBERATELY NOT PORTED.** Recorded here so it is a visible decision rather
   than a silent loss. The old iOS repo also carried, and this repo does NOT:

   - `MapMetrics_Map_Logo.pdf` replacing the upstream artwork in
     `platform/ios/resources/Images.xcassets/maplibre-logo-{icon,stroke-gray}.imageset/`
     (the old repo also RENAMED those imageset directories to `mapmetrics-*`; do not do that here —
     the asset key is referenced from `MLNMapView.mm` and renaming it is exactly the
     paths-vs-contents split that broke the old repo's Android build).
   - `LOGO_A11Y_LABEL` = `"MapMetrics"` in ~21 `platform/ios/resources/*.lproj/Localizable.strings`
     and its default in `MLNMapView.mm`. Upstream ships `"Mapbox"` there.
   - `MapMetrics.docc` / `MapMetrics.podspec` / `mapmetrics-app-icon.png` renames.

   Symptom: the iOS map renders with MapLibre branding and announces "Mapbox" to VoiceOver.
   Visual/accessibility only — no functional or billing impact, and NOTHING TURNS RED.
   KNOWN ISSUE if it is ever ported: `MapMetrics_Map_Logo.pdf` is a white-filled wordmark with a
   thin grey stroke, measured at a **1.06:1** contrast ratio against the production light style
   where WCAG AA wants 3.0:1 — the fill contributes no contrast at all. Upstream's asset (hence
   the `stroke-gray` key name) uses glyphs filled `#000` at 40% opacity on a `#fff` halo, which
   works on both. Replacing the PDF with artwork built that way is the whole fix; no code change.

## Verification — REQUIRED, do not skip

A re-vendor that drops any of the above still COMPILES. Silence is not success.

**BOTH PLATFORMS, EVERY TIME. A green iOS build proves nothing about Android, and vice versa** —
the two share almost no build files. Android was broken in the old iOS fork from the day it was
created and nobody noticed for exactly this reason: that repo shipped iOS, so nobody ran the
Android build. Now that one repo carries both, that excuse is gone.

Before anything else, make sure the vendored submodules are actually POPULATED. A `--depth 1`
submodule clone lands on the remote's default branch, which usually does not contain the pinned
SHA, and git then leaves the working tree EMPTY — bazel fails later with a confusing
"No MODULE.bazel ... found in vendor/maplibre-tile-spec" or an `allow_empty` glob error, which
looks like a build-file bug and is not:

    git submodule update --init --recursive --force -j 8   # note: NO --depth
    git submodule status --recursive | grep -c '^-'        # expect 0

### Android

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

### iOS / darwin

Upstream pins the Bazel version in `.bazelversion` (currently `8.6.0`); the `bazel` wrapper prints
an error and **exits 0** on a version mismatch, so a "successful" build that produced nothing is
usually this. Check `bazel --version` matches, or use `bazelisk` / the pinned binary directly.

    bazel build //platform/ios:MapLibre.dynamic \
      --//:renderer=metal --apple_platform_type=ios --ios_minimum_os=12.0
    bazel test //platform/ios/test:ios_test \
      --//:renderer=metal --apple_platform_type=ios --ios_minimum_os=12.0

Post-port the suite is **321 tests, 0 failures, 7 skipped**, of which 45 are `MMMapSessionTests`
and 4 are the `MM_STAGING_KEY`-gated `MMMapSessionIntegrationTests`. Bazel reports this as
"1 test target"; the real counts are in
`"$(bazel info bazel-testlogs)/platform/ios/test/ios_test/test.log"`:

    grep "Executed .* tests" "$(bazel info bazel-testlogs)/platform/ios/test/ios_test/test.log"

Note the target is `MapLibre.dynamic`, NOT `MapMetrics.dynamic` — the old iOS repo renamed it as
part of the full rebrand this repo does not do. Likewise `platform/ios/MapLibre.podspec` and the
`org.maplibre` Android packages keep upstream spelling. See the minimal-rebrand note at the top.

Then the greps that catch what a green build does not — B and D above are BOTH silent failures,
so run all four every time:

    grep -c MMMapSession platform/darwin/bazel/files.bzl        # expect 5
    grep -c 'Reason::Server' platform/darwin/core/http_file_source.mm  # expect >= 1
    grep -c MM_STAGING_KEY platform/ios/test/BUILD.bazel        # expect 1
    grep -c 'NSTimer\|scheduledTimer' platform/darwin/src/MMMapSession.mm  # expect 0

Coverage is not uniform here either:

- Patch A (session behaviour) is well covered — 45 unit cases, all offline.
- Patch B is covered by NO test. A dropped `files.bzl` entry compiles and links green with the
  session code absent. Use the `bazel query` + `nm` proof given with the patch; the unit tests
  link the sources directly and will keep passing even when the SHIPPED FRAMEWORK has none.
- Patch C is covered by no darwin test (no ObjC test executes that C++ path). Android has a
  source-level guard for its sibling patch; darwin has none. Grep it by eye.
- Patch D: verify by the absence of the skip message, not by a green suite — see the patch.
- Patches E and F are NOT COVERED AT ALL and report fully green if dropped.
