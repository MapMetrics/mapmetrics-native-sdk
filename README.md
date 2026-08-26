<p align="center">
  <img src="https://github.com/user-attachments/assets/7ff2cda8-f564-4e70-a971-d34152f969f0#gh-light-mode-only" alt="MapLibre Logo" width="200">
  <img src="https://github.com/user-attachments/assets/cee8376b-9812-40ff-91c6-2d53f9581b83#gh-dark-mode-only" alt="MapLibre Logo" width="200">
</p>

# MapMetrics Native

[![codecov](https://codecov.io/github/maplibre/maplibre-native/branch/main/graph/badge.svg?token=8ZQRRY56ZA)](https://codecov.io/github/maplibre/maplibre-native) [![](https://img.shields.io/badge/Slack-%23maplibre--native-2EB67D?logo=slack)](https://slack.openstreetmap.us/)

MapMetrics Native is a free and open-source library for publishing maps in your apps and desktop applications on various platforms. Fast displaying of maps is possible thanks to GPU-accelerated vector tile rendering.

This project originated as a fork of Mapbox GL Native, before their switch to a non-OSS license in December 2020. For more information, see: [`FORK.md`](./FORK.md).

<p align="center">
  <img src="https://user-images.githubusercontent.com/649392/211550776-8779041a-7c12-4bed-a7bd-c2ec80af2b29.png" alt="Android device with MapMetrics" width="24%">   <img src="https://user-images.githubusercontent.com/649392/211550762-0f42ebc9-05ab-4d89-bd59-c306453ea9af.png" alt="iOS device with MapMetrics" width="25%">
</p>

## Map styles

### Rendering a map with no API key

The gateway serves a **demo style** that needs no credential:

```
https://gateway.mapmetrics-atlas.net/demo/style.json
```

Every example app in this repository uses it, which is why they run straight
from a clean checkout. It is rate-limited, capped at zoom 12 and watermarked
— enough to confirm a setup works, deliberately not enough to ship. Get a real
key at https://mapatlas.eu.

### Where the style is configured

One constant per platform sits behind every example:

| Platform | File | Constant |
|---|---|---|
| Android | `platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/styles/TestStyles.kt` | `MAPMETRICS_DEMO` |
| iOS (Swift) | `platform/ios/app-swift/Sources/Styles.swift` | `MAPMETRICS_DEMO_STYLE` |
| iOS (ObjC) | `platform/ios/app/MBXViewController.mm`, `MBXSnapshotsViewController.m` | URL literal — no shared constant |

Some examples deliberately do **not** use the demo style, each for a reason
recorded next to it: the offline examples (bulk region download is exactly
what the demo's rate limit and zoom cap exist to refuse), `PMTilesExample`
(different protocol), `CameraSliderExample` (satellite imagery we do not
serve), and `DDSCircleLayerExample`'s data source (OpenMapTiles schema, while
the demo tiles are Protomaps).

### A caveat worth knowing

If your code sets **no** style at all, you do not get the MapMetrics demo —
you get MapLibre's. `TileServerOptions::DefaultConfiguration()` returns
`MapLibreConfiguration()`, based at `https://demotiles.maplibre.org`
(`src/mbgl/util/tile_server_options.cpp`). That default is shared by iOS and
Android. Always name a style explicitly until that is changed.

## Building the example apps

The iOS apps build for the **simulator with no Apple Developer account** — a
provisioning profile is required only for device builds:

```bash
bazel build //platform/ios:App --//:renderer=metal --ios_multi_cpus=sim_arm64
```

Use `--ios_multi_cpus`, not `--platforms`: `ios_application` applies its own
platform transition, so `--platforms` does not reach the rule and a build you
believe is targeting a device may quietly be a simulator build.

For device builds, copy `platform/darwin/bazel/example_config.bzl` to
`config.bzl` (gitignored) and set the profile name and team ID to a profile
installed on your machine.

## Getting Started

### Android

Add [the latest version](https://central.sonatype.com/artifact/org.maplibre.gl/android-sdk/versions) of MapMetrics Native Android as a dependency to your project.

```gradle
    dependencies {
        ...
        implementation 'org.mapmetrics.android-sdk:$latest-version'
        ...
    }
```

Add a `MapView` to your layout XML file:

```xml
<org.maplibre.android.maps.MapView
    android:id="@+id/mapView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    />
```

> [!TIP]
> There are external projects such as [Ramani Maps](https://github.com/ramani-maps/ramani-maps) and [MapLibre Compose Playground](https://github.com/Rallista/maplibre-compose-playground) available to integrate MapMetrics Native Android with Compose-based projects.

Next, initialize the map in an activity:

<details><summary>Show code</summary>

```kotlin
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.testapp.R

class MainActivity : AppCompatActivity() {

    // Declare a variable for MapView
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init MapLibre
        MapLibre.getInstance(this)

        // Init layout view
        val inflater = LayoutInflater.from(this)
        val rootView = inflater.inflate(R.layout.activity_main, null)
        setContentView(rootView)

        // Init the MapView
        mapView = rootView.findViewById(R.id.mapView)
        mapView.getMapAsync { map ->
            map.setStyle("https://demotiles.maplibre.org/style.json")
            map.cameraPosition = CameraPosition.Builder().target(LatLng(0.0,0.0)).zoom(1.0).build()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
```
</details>

For more information, refer to the [Android API Documentation](https://maplibre.org/maplibre-native/android/api/) or the [Android Examples Documentation](https://maplibre.org/maplibre-native/android/examples/getting-started/).

## iOS

You can find MapMetrics Native iOS on [Cocoapods](https://cocoapods.org/) and on the [Pods](https://cocoapods.org/pods/MapMetrics).

MapMetrics Native iOS uses UIKit. To integrate it with an UIKit project, you can use

```swift
class SimpleMap: UIViewController, MLNMapViewDelegate {
    var mapView: MLNMapView!

    override func viewDidLoad() {
        super.viewDidLoad()
        mapView = MLNMapView(frame: view.bounds)
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(mapView)
        mapView.delegate = self
    }

    func mapView(_: MLNMapView, didFinishLoading _: MLNStyle) {
    }
}
```

You need to create a wrapper when using SwiftUI.

```swift
import MapMetrics

struct SimpleMap: UIViewRepresentable {
    func makeUIView(context _: Context) -> MLNMapView {
        let mapView = MLNMapView()
        return mapView
    }

    func updateUIView(_: MLNMapView, context _: Context) {}
}
```

The [iOS Documentation](https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/) contains many examples and the entire API of the library.

## Contributing

> [!NOTE]
> This section is only relevant for people who want to contribute to MapMetrics Native.

MapMetrics Native has at its core a C++ library. This is where the bulk of development is currently happening.

To get started with the code base, you need to clone the repository including all its submodules.

All contributors use pull requests from a private fork. [Fork the project](https://github.com/maplibre/maplibre-native/fork). Then run:

```bash
git clone --recurse-submodules git@github.com:<YOUR NAME>/mapmetrics-native-sdk.git
git remote add origin https://github.com/maplibre/mapmetrics-native-sdk.git
```

The go-to reference is the [MapMetrics Native Developer Documentation](https://maplibre.org/maplibre-native/docs/book/).

> [!TIP]
> Check out issues labelled as a [good first issue](https://github.com/maplibre/maplibre-native/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).

### Core

- [`CONTRIBUTING.md`](CONTRIBUTING.md)
- [GitHub Wiki](https://github.com/maplibre/maplibre-native/wiki): low-friction way to share information with the community
- [Core C++ API Documentation](https://maplibre.org/maplibre-native/cpp/api/) (unstable)

### Android

Open `platform/android` with Android Studio.

More information: [MapMetrics Android Developer Guide](https://maplibre.org/maplibre-native/docs/book/android/index.html).

### iOS

You need to use [Bazel](https://bazel.build/) to generate an Xcode project. Install [`bazelisk`](https://formulae.brew.sh/formula/bazelisk) (a wrapper that installs the required Bazel version). Next, use:

```bash
bazel run //platform/ios:xcodeproj --@rules_xcodeproj//xcodeproj:extra_common_flags="--//:renderer=metal"
xed platform/ios/MapLibre.xcodeproj
```

To generate and open the Xcode project.

More information: [MapMetrics iOS Developer Guide](https://maplibre.org/maplibre-native/docs/book/ios/index.html).

## Other Platforms

See [`/platform`](/platform) and navigate to the platform you are interested in for more information.


## License

**MapMetrics Native** is licensed under the [BSD 2-Clause License](./LICENSE.md).
