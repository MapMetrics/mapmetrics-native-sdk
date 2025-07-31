# Configuration

This guide will explain various ways to create a map.

When working with maps, you likely want to configure the `MapView`.

There are several ways to build a `MapView`:

1. Using existing XML namespace tags for`MapView` in the layout.
2. Creating `MapLibreMapOptions` and passing builder function values into the `MapView`.
3. Creating a `SupportMapFragment` with the help of `MapLibreMapOptions`.

Before diving into `MapView` configurations, let's understand the capabilities of both XML namespaces and `MapLibreMapOptions`.

Here are some common configurations you can set:

- Map base URI
- Camera settings
- Zoom level
- Pitch
- Gestures
- Compass
- Logo
- Attribution
- Placement of the above elements on the map and more

We will explore how to achieve these configurations in XML layout and programmatically in Activity code, step by step.

### `MapView` Configuration with an XML layout

To configure `MapView` within an XML layout, you need to use the right namespace and provide the necessary data in the layout file.

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".activity.options.MapOptionsXmlActivity">

    <org.maplibre.android.maps.MapView
        android:id="@+id/mapView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:maplibre_apiBaseUri="https://api.maplibre.org"
        app:maplibre_cameraBearing="0.0"
        app:maplibre_cameraPitchMax="90.0"
        app:maplibre_cameraPitchMin="0.0"
        app:maplibre_cameraTargetLat="42.31230486601532"
        app:maplibre_cameraTargetLng="64.63967338936439"
        app:maplibre_cameraTilt="0.0"
        app:maplibre_cameraZoom="3.9"
        app:maplibre_cameraZoomMax="26.0"
        app:maplibre_cameraZoomMin="2.0"
        app:maplibre_localIdeographFontFamilies="@array/array_local_ideograph_family_test"
        app:maplibre_localIdeographFontFamily="Droid Sans"
        app:maplibre_uiCompass="true"
        app:maplibre_uiCompassFadeFacingNorth="true"
        app:maplibre_uiCompassGravity="top|end"
        app:maplibre_uiDoubleTapGestures="true"
        app:maplibre_uiHorizontalScrollGestures="true"
        app:maplibre_uiRotateGestures="true"
        app:maplibre_uiScrollGestures="true"
        app:maplibre_uiTiltGestures="true"
        app:maplibre_uiZoomGestures="true" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

This can be found in [`activity_map_options_xml.xml`](https://github.com/MapMetrics/mapmetrics-native-sdk/blob/main/platform/android/MapLibreAndroidTestApp/src/main/res/layout/activity_map_fragment.xml).

You can assign any other existing values to the `maplibre...` tags. Then, you only need to create `MapView` and `MapLibreMap` objects with a simple setup in the Activity.

```kotlin title="MapOptionsXmlActivity.kt"
package org.maplibre.android.testapp.activity.options

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.styles.TestStyles

/**
 *  TestActivity demonstrating configuring MapView with XML
 */

class MapOptionsXmlActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapView: MapView
    private lateinit var maplibreMap: MapLibreMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_options_xml)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    override fun onMapReady(maplibreMap: MapLibreMap) {
        this.maplibreMap = maplibreMap
        this.maplibreMap.setStyle("https://demotiles.maplibre.org/style.json")
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
```

This can be found in [`MapOptionsXmlActivity.kt`](https://github.com/MapMetrics/mapmetrics-native-sdk/blob/main/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/options/MapOptionsXmlActivity.kt).

### `MapView` configuration with `MapLibreMapOptions`

 Here we don't have to create MapView from XML since we want to create it programmatically.
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"/>
```

This can be found in [`activity_map_options_runtime.xml`](https://github.com/MapMetrics/mapmetrics-native-sdk/blob/main/platform/android/MapLibreAndroidTestApp/src/main/res/layout/activity_map_options_runtime.xml).

A `MapLibreMapOptions` object must be created and passed to the MapView constructor. All setup is done in the Activity code:

```kotlin title="MapOptionsRuntimeActivity.kt"
package org.maplibre.android.testapp.activity.options

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.styles.TestStyles

/**
 *  TestActivity demonstrating configuring MapView with MapOptions
 */
class MapOptionsRuntimeActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var maplibreMap: MapLibreMap
    private lateinit var mapView: MapView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_options_runtime)

        // Create map configuration
        val maplibreMapOptions = MapLibreMapOptions.createFromAttributes(this)
        maplibreMapOptions.apply {
            apiBaseUri("https://api.maplibre.org")
            camera(
                CameraPosition.Builder()
                    .bearing(0.0)
                    .target(LatLng(42.31230486601532, 64.63967338936439))
                    .zoom(3.9)
                    .tilt(0.0)
                    .build()
            )
            maxPitchPreference(90.0)
            minPitchPreference(0.0)
            maxZoomPreference(26.0)
            minZoomPreference(2.0)
            localIdeographFontFamily("Droid Sans")
            zoomGesturesEnabled(true)
            compassEnabled(true)
            compassFadesWhenFacingNorth(true)
            scrollGesturesEnabled(true)
            rotateGesturesEnabled(true)
            tiltGesturesEnabled(true)
        }

        // Create map programmatically, add to view hierarchy
        mapView = MapView(this, maplibreMapOptions)
        mapView.getMapAsync(this)
        mapView.onCreate(savedInstanceState)
        (findViewById<View>(R.id.container) as ViewGroup).addView(mapView)
    }

    override fun onMapReady(maplibreMap: MapLibreMap) {
        this.maplibreMap = maplibreMap
        this.maplibreMap.setStyle("https://demotiles.maplibre.org/style.json")
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
```

This can be found in [`MapOptionsRuntimeActivity.kt`](https://github.com/MapMetrics/mapmetrics-native-sdk/blob/main/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/options/MapOptionsRuntimeActivity.kt).

[//]: # (Finally you will see a result similar to this:)

[//]: # ()
[//]: # (<div style="text-align: center">)

[//]: # (  <img src="https://github.com/user-attachments/assets/dd85f496-3e6f-4788-933e-4ec3d5999935" alt="Screenshot with the MapLibreMapOptions">)

[//]: # (</div>)

For the full contents of `MapOptionsRuntimeActivity` and `MapOptionsXmlActivity`, please take a look at the source code of [MapLibreAndroidTestApp](https://github.com/MapMetrics/mapmetrics-native-sdk/tree/main/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/options).

You can read more about `MapLibreMapOptions` in the [Android API documentation](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map-options/index.html?query=open%20class%20MapLibreMapOptions%20:%20Parcelable).

### `SupportMapFragment` with the help of `MapLibreMapOptions`.

If you are using MapFragment in your project, it is also easy to provide initial values to the `newInstance()` static method of `SupportMapFragment`, which requires a `MapLibreMapOptions` parameter.

Let's see how this can be done in a sample activity:

```kotlin
package org.maplibre.android.testapp.activity.fragment

import android.os.Bundle // ktlint-disable import-ordering
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.* // ktlint-disable no-wildcard-imports
import org.maplibre.android.maps.MapFragment.OnMapViewReadyCallback
import org.maplibre.android.maps.MapView.OnDidFinishRenderingFrameListener
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.styles.TestStyles

/**
 * Test activity showcasing using the MapFragment API using Support Library Fragments.
 *
 *
 * Uses MapLibreMapOptions to initialise the Fragment.
 *
 */
class SupportMapFragmentActivity :
    AppCompatActivity(),
    OnMapViewReadyCallback,
    OnMapReadyCallback,
    OnDidFinishRenderingFrameListener {
    private lateinit var maplibreMap: MapLibreMap
    private lateinit var mapView: MapView
    private var initialCameraAnimation = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_fragment)
        val mapFragment: SupportMapFragment?
        if (savedInstanceState == null) {
            mapFragment = SupportMapFragment.newInstance(createFragmentOptions())
            supportFragmentManager
                .beginTransaction()
                .add(R.id.fragment_container, mapFragment, TAG)
                .commit()
        } else {
            mapFragment = supportFragmentManager.findFragmentByTag(TAG) as SupportMapFragment?
        }
        mapFragment!!.getMapAsync(this)
    }

    private fun createFragmentOptions(): MapLibreMapOptions {
        val options = MapLibreMapOptions.createFromAttributes(this, null)
        options.scrollGesturesEnabled(false)
        options.zoomGesturesEnabled(false)
        options.tiltGesturesEnabled(false)
        options.rotateGesturesEnabled(false)
        options.debugActive(false)
        val dc = LatLng(38.90252, -77.02291)
        options.minZoomPreference(9.0)
        options.maxZoomPreference(11.0)
        options.camera(
            CameraPosition.Builder()
                .target(dc)
                .zoom(11.0)
                .build()
        )
        return options
    }

    override fun onMapViewReady(map: MapView) {
        mapView = map
        mapView.addOnDidFinishRenderingFrameListener(this)
    }

    override fun onMapReady(map: MapLibreMap) {
        maplibreMap = map
        maplibreMap.setStyle(TestStyles.getPredefinedStyleWithFallback("Satellite Hybrid"))
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.removeOnDidFinishRenderingFrameListener(this)
    }

    override fun onDidFinishRenderingFrame(fully: Boolean, frameEncodingTime: Double, frameRenderingTime: Double) {
        if (initialCameraAnimation && fully && this::maplibreMap.isInitialized) {
            maplibreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(45.0).build()),
                5000
            )
            initialCameraAnimation = false
        }
    }

    companion object {
        private const val TAG = "com.mapbox.map"
    }
}
```

You can also find the full contents of `SupportMapFragmentActivity` in the [MapLibreAndroidTestApp](https://github.com/MapMetrics/mapmetrics-native-sdk/tree/main/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/fragment/SupportMapFragmentActivity.kt).

To learn more about `SupportMapFragment`, please visit the [Android API documentation](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-support-map-fragment/index.html?query=open%20class%20SupportMapFragment%20:%20Fragment,%20OnMapReadyCallback).
