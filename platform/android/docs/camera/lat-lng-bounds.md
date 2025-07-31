# LatLngBounds API

{{ activity_source_note("LatLngBoundsActivity.kt") }}

[//]: # (This example demonstrates setting the camera to some bounds defined by some features. It sets these bounds when the map is initialized and when the [bottom sheet]&#40;https://m2.material.io/components/sheets-bottom&#41; is opened or closed.)

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/lat_lng_bounds_thumbnail.jpg">)

[//]: # (    <source src="https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/lat_lng_bounds.mp4" />)

[//]: # (  </video>)

[//]: # (</figure>)


Here you can see how the feature collection is loaded and how `MapLibreMap.getCameraForLatLngBounds` is used to set the bounds during map initialization:

```kotlin
val featureCollection: FeatureCollection =
    fromJson(GeoParseUtil.loadStringFromAssets(this, "points-sf.geojson"))
bounds = createBounds(featureCollection)

map.getCameraForLatLngBounds(bounds, createPadding(peekHeight))?.let {
    map.cameraPosition = it
}
```

The `createBounds` function uses the `LatLngBounds` API to include all points within the bounds:

```kotlin
private fun createBounds(featureCollection: FeatureCollection): LatLngBounds {
    val boundsBuilder = LatLngBounds.Builder()
    featureCollection.features()?.let {
        for (feature in it) {
            val point = feature.geometry() as Point
            boundsBuilder.include(LatLng(point.latitude(), point.longitude()))
        }
    }
    return boundsBuilder.build()
}
```
