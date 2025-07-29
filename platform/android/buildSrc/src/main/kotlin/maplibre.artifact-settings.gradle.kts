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

// Handling conditional assignment for versionName
extra["versionName"] = if (project.hasProperty("VERSION_NAME")) {
    project.property("VERSION_NAME")
} else {
    System.getenv("VERSION_NAME")
}
