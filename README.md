# MapMetrics Native

[![codecov](https://codecov.io/github/maplibre/maplibre-native/branch/main/graph/badge.svg?token=8ZQRRY56ZA)](https://codecov.io/github/maplibre/maplibre-native)

MapMetrics Native is a free and open-source library for publishing maps in your apps and desktop applications on various platforms. Fast displaying of maps is possible thanks to GPU-accelerated vector tile rendering.

This project originated as a fork of Mapbox GL Native, before their switch to a non-OSS license in December 2020. For more information, see: [`FORK.md`](./FORK.md).


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
> There are external projects such as [Ramani Maps](https://github.com/ramani-maps/ramani-maps) and [MapLibre Compose Playground](https://github.com/Rallista/maplibre-compose-playground) available to intergrate MapMetrics Native Android with Compose-based projects.

Next, initialize the map in an activity:

<details><summary>Show code</summary>

```kotlin
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import org.maplibre.android.Maplibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.testapp.R

class SimpleMapActivity : AppCompatActivity() {

    // Declare a variable for MapView
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // activity uses singleInstance for testing purposes
                // code below provides a default navigation when using the app
                NavUtils.navigateHome(this@SimpleMapActivity)
            }
        })
        setContentView(R.layout.activity_map_simple)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync {
            val key = ApiKeyUtils.getApiKey(applicationContext)
            if (key == null || key == "YOUR_API_KEY_GOES_HERE") {
                it.setStyle(
                    Style.Builder().fromUri(style)
                )
            } else {
                val styles = Style.getPredefinedStyles()
                if (styles.isNotEmpty()) {
                    val styleUrl = styles[0].url
                    it.setStyle(Style.Builder().fromUri(styleUrl))
                }
            }
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

MapMetrics Native iOS uses UIKit. To intergrate it with an UIKit project, you can use

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

To get started with the code base, you need to clone the the repository including all its submodules.

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
