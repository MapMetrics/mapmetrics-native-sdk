extra["mapLibreArtifactGroupId"] = "org.mapmetrics.android-sdk"
extra["mapLibreArtifactId"] = "mapmetrics-native-sdk"
extra["mapLibreArtifactTitle"] = "MapMetrics Android"
extra["mapLibreArtifactDescription"] = "MapMetrics Android"
extra["mapLibreDeveloperName"] = "MapMetrics"
extra["mapLibreDeveloperId"] = "mapMetrics"
// Declared HERE, beside the other developer fields, rather than inline in
// maplibre.gradle-publish. It was hardcoded there as team@maplibre.org and
// silently regressed the POM: the published 1.0.3 carries Jack@mapmetrics.org,
// so the consolidation would have shipped MapLibre's address under our
// coordinates. A POM cannot be corrected after release -- only superseded by a
// new version -- so this belongs with the settings that get reviewed together.
extra["mapLibreDeveloperEmail"] = "jack@mapmetrics.org"
extra["mapLibreArtifactUrl"] = "https://github.com/MapMetrics/mapmetrics-native-sdk"
extra["mapLibreArtifactScmUrl"] = "scm:git@github.com:MapMetrics/mapmetrics-native-sdk.git"
extra["mapLibreArtifactLicenseName"] = "BSD"
extra["mapLibreArtifactLicenseUrl"] = "https://opensource.org/licenses/BSD-2-Clause"

val versionFilePath = rootDir.resolve("VERSION")
val versionName = if (versionFilePath.exists()) {
    versionFilePath.readText().trim()
} else {
    throw GradleException("VERSION file not found at ${versionFilePath.absolutePath}")
}

extra["versionName"] = versionName
