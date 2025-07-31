# LocationComponent

This guide will demonstrate how to utilize the [LocationComponent] to represent the user's current location.


When implementing the [LocationComponent], the application should request location permissions.  Declare the need for foreground location in the `AndroidManifest.xml` file. For more information, please refer to the [Android Developer Documentation].

```xml
<manifest ... >
  <!-- Always include this permission -->
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

  <!-- Include only if your app benefits from precise location access. -->
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
</manifest>
```

Create a new activity named `BasicLocationPulsingCircleActivity`:

- This Activity should implement the `OnMapReadyCallback` interface. The `onMapReady()` method is triggered when the map is ready to be used.
- Add a variable `permissionsManager` to manage permissions.
- Add a variable `locationComponent` to manage user location.
- At the end of the `onCreate()` method, call `checkPermissions()` to ensure that the application can access the user's location.

```kotlin
/**
 * This activity shows a basic usage of the LocationComponent's pulsing circle. There's no
 * customization of the pulsing circle's color, radius, speed, etc.
 */
class BasicLocationPulsingCircleActivity : AppCompatActivity(), OnMapReadyCallback {
    private var lastLocation: Location? = null
    private lateinit var mapView: MapView
    private var permissionsManager: PermissionsManager? = null
    private var locationComponent: LocationComponent? = null
    private lateinit var maplibreMap: MapLibreMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_layer_basic_pulsing_circle)
        mapView = findViewById(R.id.mapView)
        if (savedInstanceState != null) {
            lastLocation = savedInstanceState.getParcelable(SAVED_STATE_LOCATION, Location::class.java)
        }
        mapView.onCreate(savedInstanceState)
        checkPermissions()
    }}
```

In the `checkPermissions()` method, the [PermissionManager] is used to request location permissions at runtime and handle the callbacks for permission granting or rejection.Additionally, you should pass the results of `Activity.onRequestPermissionResult()` to it. If the permissions are granted, call `mapView.getMapAsync(this)` to register the activity as a listener for onMapReady event.

```kotlin
private fun checkPermissions() {
      if (PermissionsManager.areLocationPermissionsGranted(this)) {
            mapView.getMapAsync(this)
      } else {
            permissionsManager = PermissionsManager(object : PermissionsListener {
                  override fun onExplanationNeeded(permissionsToExplain: List<String>) {
                        Toast.makeText(
                              this@BasicLocationPulsingCircleActivity,
                              "You need to accept location permissions.",
                              Toast.LENGTH_SHORT
                        ).show()
                  }

                  override fun onPermissionResult(granted: Boolean) {
                        if (granted) {
                              mapView.getMapAsync(this@BasicLocationPulsingCircleActivity)
                        } else {
                              finish()
                        }
                  }
            })
            permissionsManager!!.requestLocationPermissions(this)
      }
}

override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<String>,
      grantResults: IntArray
) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
      permissionsManager!!.onRequestPermissionsResult(requestCode, permissions, grantResults)
}

```

In the `onMapReady()` method, first set the style and then handle the user's location using the [LocationComponent].

To configure the [LocationComponent], developers should use [LocationComponentOptions].

In this demonstration, we create an instance of this class.

In this method:

- Use the annotation `@SuppressLint("MissingPermission")` to suppress warnings related to missing location access permissions.
- In `setStyle(),` you can utilize other public and token-free styles like [demotiles] instead of the [predefined styles].
- For the builder of [LocationComponentOptions], use `pulseEnabled(true)` to enable the pulse animation, which enhances awareness of the user's location.
- Use method `buildLocationComponentActivationOptions()` to set [LocationComponentActivationOptions], then activate `locatinoComponent` with it.
- To apply options, make sure you call `activateLocationComponent()` of `locationComponent`. You can also set `locationComponent`'s various properties like `isLocationComponentEnabled` , `cameraMode` , etc...
- `CameraMode.TRACKING`[^1] means that when the user's location is updated, the camera will reposition accordingly.
- `locationComponent!!.forceLocationUpdate(lastLocation)` updates the the user's last known location.

```kotlin
@SuppressLint("MissingPermission")
override fun onMapReady(maplibreMap: MapLibreMap) {
      this.maplibreMap = maplibreMap
      maplibreMap.setStyle(TestStyles.getPredefinedStyleWithFallback("Streets")) { style: Style ->
            locationComponent = maplibreMap.locationComponent
            val locationComponentOptions =
                  LocationComponentOptions.builder(this@BasicLocationPulsingCircleActivity)
                        .pulseEnabled(true)
                        .build()
            val locationComponentActivationOptions =
                  buildLocationComponentActivationOptions(style, locationComponentOptions)
            locationComponent!!.activateLocationComponent(locationComponentActivationOptions)
            locationComponent!!.isLocationComponentEnabled = true
            locationComponent!!.cameraMode = CameraMode.TRACKING
            locationComponent!!.forceLocationUpdate(lastLocation)
      }
}
```

[LocationComponentActivationOptions] is used to hold the style, [LocationComponentOptions] and other locating behaviors.

- It can also be used to configure how to obtain the current location, such as [LocationEngine] and intervals.
- In this demonstration, it sets 750ms as the fastest interval for location updates, providing high accuracy location results (but with higher power consumption).
- For more information, please visit the [documentation page][LocationComponentActivationOptions].

```kotlin
private fun buildLocationComponentActivationOptions(
      style: Style,
      locationComponentOptions: LocationComponentOptions
): LocationComponentActivationOptions {
      return LocationComponentActivationOptions
            .builder(this, style)
            .locationComponentOptions(locationComponentOptions)
            .useDefaultLocationEngine(true)
            .locationEngineRequest(
                  LocationEngineRequest.Builder(750)
                        .setFastestInterval(750)
                        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                        .build()
            )
            .build()
}
```

For further customization, you can also utilize the `foregroundTintColor()` and `pulseColor()` methods on the [LocationComponentOptions] builder:

```kotlin
val locationComponentOptions =
    LocationComponentOptions.builder(this@BasicLocationPulsingCircleActivity)
       .pulseEnabled(true)
       .pulseColor(Color.RED)             // Set color of pulse
       .foregroundTintColor(Color.BLACK)  // Set color of user location
       .build()
```

[//]: # (Here is the final results with different color configurations. For the complete content of this demo, please refer to the source code of the [Test App].)

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![result]&#40;https://github.com/MapMetrics/mapmetrics-native-sdk/assets/19887090/03dfc87b-111b-4dd0-b4a3-d89e30ed6b63&#41;)

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)


[^1]: A variety of [camera modes] determine how the camera will track the user location.
      They provide the right context to your users at the correct time.

[LocationComponent]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location/-location-component/index.html
[Android Developer Documentation]: https://developer.android.com/training/location/permissions
[onMapReadyCallback]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-on-map-ready-callback/index.html
[PermissionManager]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location.permissions/-permissions-manager/index.html
[LocationComponentOptions]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location/-location-component-options/index.html
[demotiles]: https://demotiles.maplibre.org/style.json
[predefined styles]: https://github.com/MapMetrics/mapmetrics-native-sdk/tree/main/src/mbgl/util/tile_server_options.cpp
[LocationComponentActivationOptions]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location/-location-component-activation-options/index.html
[LocationEngine]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location.engine/-location-engine/index.html
[Test APP]: https://github.com/MapMetrics/mapmetrics-native-sdk/tree/main/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/location/BasicLocationPulsingCircleActivity.kt
[camera modes]: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location.modes/-camera-mode/index.html
