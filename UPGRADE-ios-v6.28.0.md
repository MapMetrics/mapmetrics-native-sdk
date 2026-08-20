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

## Phases 2-4 — upgrade, replay, verification

### Result

| | pre | post |
|---|---|---|
| tests | 961 | 968 |
| failures | 0 | 0 |
| skipped | 1 | 1 |
| passing | 960 | 967 |

The 7 extra tests are upstream's. The 1 skip is the same live-gateway cold start.

Convergence achieved: `platform/darwin`, `platform/ios`, `platform/macos`, `src/`, `include/` and
`vendor/` are byte-identical to `ios-v6.28.0`. Divergence is now 60 files, all Android, docs, or
this file.

### The Gradle situation — the anticipated risk did not materialize

The brief budgeted for a Gradle 8.13 -> 9.x toolchain fight, with publishing as the likely casualty.
It cost nothing, because upstream had already done that migration and this fork's publishing
patches were mostly *replaced by* it rather than broken by it:

- The wrapper went 8.13 -> 9.5.1 with the merge, and the suite went green on the first run with no
  intervention.
- Upstream moved off `nexusPublishing` onto `com.vanniktech.maven.publish.base`, applied per module.
  It deleted `maplibre.publish-root.gradle.kts` and rewrote `maplibre.gradle-publish.gradle.kts`.
  This fork's edits to both were workarounds for the old structure (signing disabled,
  `PublishToMavenRepository` narrowed to `PublishToMavenLocal`, an extra `bundleDrawableReleaseAar`
  dependency); upstream's rewrite handles all three properly. Both taken as-is.
- Only the durable identity survived as patches: group/artifact ids in
  `maplibre.artifact-settings.gradle.kts`, and the version, which moved to `platform/android/VERSION`.
- Remaining warning is now "incompatible with Gradle 10", one major version out. Not urgent.

Not verified: an actual `publish` to Maven Central. The suite does not exercise it and it should
not be run from a dev machine. See handover.

### Replay status

| # | item | status |
|---|------|--------|
| 1 | `InMemoryCookieJar` | **rewired.** See below. Verified by `installedClientKeepsTheCookieJar`. |
| 2 | native 401/403 -> `Reason::Server` | auto-merged clean, scoping to `Resource::Kind::Tile` intact |
| 3 | `cacheApiKey` x3 in `MapLibre.java` | auto-merged clean, all three sites |
| 4 | branding | auto-merged clean: `MapView.java`, `MapSnapshotter.kt`, `public.xml`, PNG, 22 `strings.xml` |
| 5 | build/publishing | restructured by upstream; identity re-pinned, see the Gradle section |
| 6 | test-task input declaration | survived unmodified; **proven live**, see below |
| 7 | renewal-timer removal | intact; no timer/scheduler/`postDelayed` anywhere under `session/` |

Item 1 was the only real conflict. Upstream replaced the eager `static final DEFAULT_CLIENT` with
a lazily built `static volatile defaultClient` whose builder has no cookie jar. Taking either side
whole loses something: upstream's shape silently drops the jar, this fork's shape breaks upstream's
new `HttpRequestUtilTest`, which asserts the client starts null. Resolved by keeping upstream's
laziness and adding `.cookieJar(cookieJar)` inside `getOrCreateDefaultClient()`, which is now
package-private so `MMHttpClients` can hand the same instance to the v2 signing client.

### Item 6 was tested properly, and the first result was a false alarm

`touch`ing the `.cpp` leaves the task UP-TO-DATE — Gradle hashes content, not mtime, so `touch`
proves nothing either way. A first attempt to break the patch by prefixing the branch with
`false &&` ALSO left it UP-TO-DATE, which looked like the input declaration had stopped working;
it had not. That edit preserved every string the guard matches on, so the guard would have passed
anyway, and Gradle was right to skip.

Establishing the truth took deleting the 401/403 branch outright, the way a re-vendor would:

- with the branch deleted, the task RE-RAN (not UP-TO-DATE) and
  `nativeHttpSourceStillTreats401And403AsRetryable` FAILED — correct;
- restoring the branch returned the suite to green with the task re-running again.

Both runs used the **unmodified, committed** `build.gradle.kts` with the block in its original
place inside `android { }`. A separate check confirmed `tasks.withType<Test>().configureEach`
matches all four unit-test tasks from inside that block as well as at top level, so the placement
is fine and no change was needed. An intermediate commit that moved the block to top level and
claimed the old placement was broken was wrong and has been reverted.

Lesson for the next re-vendor: to test this guard, DELETE the branch. Do not `touch` it, and do
not disable it in a way that leaves the source text intact.

## Handover — what remains

1. **Rotate the committed credentials.** The merge deleted `maplibre.publish-root.gradle.kts` and
   the root `build.gradle.kts` publishing block, which held a plaintext Sonatype username/password
   and a GPG key id + passphrase. They are gone from the working tree but remain in history, on
   this branch and on `maven_central_migration`. Treat them as compromised. Also
   `SimpleMapActivity.kt` still embeds a gateway JWT, and `MapLibreAndroid/build.gradle.kts` still
   carries a legacy GitHub Packages `publishing` block with a username in it and a hardcoded
   `/Users/macbook/Desktop/...` aar path that cannot work on any other machine.
2. **Publishing is unproven.** Upstream's vanniktech setup calls `signAllPublications()` and
   `publishToMavenCentral(true)`, and expects credentials from Gradle properties/env, not from the
   deleted file. Nobody has run a publish since the upgrade. Do a `publishToMavenLocal` first.
3. **Committed build junk**, pre-existing and untouched here: `android-binaries-1.3.290.0.zip`,
   `test/android/app/src/main/assets/data.zip` (78 MB), two `build/reports/problems-report.html`.
   Worth removing, but it is history-rewriting work and was out of scope.
4. **Live gateway path still unexercised.** `coldStartRecoversFrom401AndServesTilesOnOneCredential`
   skipped in every run here because `MM_STAGING_KEY` was not set. Nothing in this upgrade has been
   validated against the real gateway.
5. **No native build was run.** The suite uses `-Pmaplibre.abis=none`, so `http_file_source.cpp`
   was never compiled — only source-guarded. Upstream's Android layer moved substantially
   (283 files, +33917/-1505) and there are new renderer flavors (`vulkan`, `webgpuDawn`,
   `webgpuWgpu`). An actual `make android` and a device smoke test are the obvious next step, and
   are phase-2 prerequisites.
6. **Phase 2 (porting the iOS work) is unblocked**: the darwin/ios/macos/src/include trees now
   match the iOS fork's baseline exactly.
7. **Run `git submodule update --init --recursive`** before any native build. `git status` shows
   `vendor/PMTiles`, `vendor/Vulkan-Headers` and `vendor/boost` as modified: the committed gitlinks
   are correct and match the tag, but the on-disk working copies are still at the old commits.

## Commits on this branch (local only — nothing pushed)

    cbd8d7cf37 fix(vendor): re-align submodules to ios-v6.28.0
    8f647db009 feat(android): finish the ios-v6.28.0 upgrade and re-verify every fork patch
    d4a055b72f merge: upgrade to upstream MapLibre Native ios-v6.28.0
    7d0cdc31ce docs(upgrade): record pre-upgrade state and correct the stale fork manifest

Branch `feat/upgrade-ios-v6.28.0`, off `feat/v2-map-sessions` at `f7946bcc99`.
