Pod::Spec.new do |s|
    version = "#{ENV['VERSION']}"

    s.name = 'MapMetrics-SDK'
    s.version = version
    s.license = { :type => 'BSD', :file => "LICENSE.md" }
    s.homepage = 'https://mapmetrics.org/'
    s.authors = { 'MapMetrics' => '' }
    s.summary = 'Open source vector map solution for iOS with full styling capabilities.'
    s.platform = :ios
    s.source = {
        :http => "https://github.com/MapMetrics/mapmetrics-native-sdk/releases/download/ios-v#{version.to_s}/MapLibre.dynamic.xcframework.zip",
        :type => "zip"
    }
    s.ios.deployment_target = '12.0'
    # NOTE: the pod is `mapmetrics` but the framework inside is still
    # MapLibre.xcframework, so consumers write `pod 'MapMetrics-SDK'` and then
    # `import MapLibre`. That is deliberate: MAPMETRICS-FORK.md records this repo
    # as a MINIMAL rebrand -- the bazel target is `MapLibre.dynamic`, the Android
    # packages stay `org.maplibre`, and renaming the framework would change the
    # module name every consumer imports. Renaming the pod is a publishing
    # decision; renaming the framework is an API break, and they are separate.
    s.ios.vendored_frameworks = "MapLibre.xcframework"
end
