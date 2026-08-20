# Upgrade to upstream MapLibre Native `ios-v6.28.0` — working record

Target tag: `ios-v6.28.0` = `18545ca10d296704b2cba675b182ff2c8da24588` (2026-07-23).
Chosen because the sibling iOS fork (`Native-MapMetrics-iOS-SDK`) was snapshotted from exactly this
tag. Upgrading to it makes the two trees CONVERGE; a newer tag would not.

Fork point of this repo: `dfa80771346ffba43999aa890578e796c73bd755` (2025-04-22).
Delta fork-point -> tag: 2156 files, +243776 / -82772. Of that, `platform/android` alone is
283 files, +33917 / -1505.

## Phase 1 — pre-upgrade state (recorded before any upgrade work)

### Baseline test run

    cd platform/android && make run-android-unit-test    # BUILD SUCCESSFUL in 26s

Tallied from `MapLibreAndroid/build/test-results/testDrawableDebugUnitTest/TEST-*.xml`:

| tests | failures+errors | skipped | passing |
|-------|-----------------|---------|---------|
| 961   | 0               | 1       | 960     |

The 1 skipped is `MMMapSessionIntegrationTest.coldStartRecoversFrom401AndServesTilesOnOneCredential`,
which skips unless `MM_STAGING_KEY` is set. Expected; documented in `MAPMETRICS-FORK.md`.

### Marker check — every replay item verified present at HEAD (`f7946bcc99`)

| # | item | marker searched | result |
|---|------|-----------------|--------|
| 1 | `InMemoryCookieJar` | `git grep -n InMemoryCookieJar` | FOUND — `HttpRequestImpl.java:55` (field), `:69` (class) |
| 2 | native 401/403 retry | `Reason::Server` in `src/cpp/http_file_source.cpp` | FOUND — lines 190, 218, 238 + rationale comment at 204 |
| 3 | v2 install hook | `cacheApiKey` in `MapLibre.java` | FOUND — all THREE sites: `:77` (null), `:122`, `:159` |
| 4 | branding | `maplibre_mapmetrics_map_logo` | FOUND — `MapView.java:234`, `MapSnapshotter.kt:575`, `public.xml:101`, PNG + 22 `values-*/strings.xml` |
| 5 | test-task input decl | `MAPMETRICS PATCH` in `MapLibreAndroid/build.gradle.kts` | FOUND — line 125 |
| 6 | renewal-timer removal | `f7946bcc99` is HEAD | PRESENT — tip commit of `feat/v2-map-sessions` |

Session sources all present: `session/MMMapSession.kt`, `session/MMMapSessionInterceptor.kt`,
`module/http/MMHttpClients.java`, plus the three test files.

### Full divergence inventory — 63 files

`git diff --name-only dfa8077134..HEAD` — 63 files, +5369 / -295.

### `MAPMETRICS-FORK.md` IS STALE — corrections found in Phase 1

1. **Wrong path.** The manifest names `platform/android/gradle.properties`. The file that actually
   diverges is `platform/android/MapLibreAndroid/gradle.properties` (`VERSION_NAME=1.0.1`, was
   `11.8.6`). The path in the manifest does not diverge at all.
2. **Four publishing files the manifest does not list at all**, all of which carry real MapMetrics
   config and would be silently reverted by a re-vendor:
   - `platform/android/build.gradle.kts` — replaces `nexusPublishing` with plain `publishing` for the
     new Maven Central flow.
   - `buildSrc/src/main/kotlin/maplibre.artifact-settings.gradle.kts` — group id
     `org.mapmetrics.android-sdk`, artifact `mapmetrics-native-sdk`, MapMetrics developer/SCM fields.
     This is the "a wrong group id publishes cleanly" failure the manifest warns about, and the
     manifest does not say where the group id lives.
   - `buildSrc/src/main/kotlin/maplibre.gradle-publish.gradle.kts` — signing disabled, `PublishToMavenRepository`
     narrowed to `PublishToMavenLocal`, extra `bundleDrawableReleaseAar` dependency.
   - `buildSrc/src/main/kotlin/maplibre.publish-root.gradle.kts` — credentials.
3. **Two more diverging files the manifest does not list:**
   - `MapLibreAndroidTestApp/.../SimpleMapActivity.kt` — demo style URL points at the MapMetrics
     gateway with an embedded JWT. Test-app only.
   - `qt_attribution.json` — homepage URL.
4. **Committed build junk that should not be in the tree** and is not a patch:
   `platform/android/MapLibreAndroid/android-binaries-1.3.290.0.zip`,
   `test/android/app/src/main/assets/data.zip` (78 MB), and two
   `build/reports/problems/problems-report.html` files.

### SECURITY — plaintext credentials committed on this branch

`maplibre.publish-root.gradle.kts` and `platform/android/build.gradle.kts` contain a hardcoded
Sonatype username/password and a GPG signing key id + passphrase, replacing upstream's
`System.getenv(...)` reads. `SimpleMapActivity.kt` embeds a gateway JWT. These predate this upgrade
(they arrived on `maven_central_migration`) and are out of scope here, but they must be rotated and
moved back to environment variables before this repo is ever pushed.

### Toolchain baseline

- Gradle wrapper here: **8.13**. Daemon JVM: Temurin 22. System `java`: 21.0.7.
- Gradle wrapper at `ios-v6.28.0`: **9.5.1**. This is the anticipated risk, and it is real: the
  upgrade carries a major Gradle version bump, and the publishing config above is exactly the part
  that Gradle 9 breaks.
- Upstream has also removed `VERSION_NAME` from the top of `MapLibreAndroid/gradle.properties`,
  so patch item 5's version pin has no longer got the same home.
