extra["mapLibreArtifactGroupId"] = "org.mapmetrics.android-sdk"
extra["mapLibreArtifactId"] = "mapmetrics-native-sdk"
extra["mapLibreArtifactTitle"] = "MapMetrics Android"
extra["mapLibreArtifactDescription"] = "MapMetrics Android"
extra["mapLibreDeveloperName"] = "MapMetrics"
extra["mapLibreDeveloperId"] = "mapMetrics"
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
