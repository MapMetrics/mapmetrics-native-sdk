# Add live realtime data

[//]: # ({{ activity_source_note&#40;"RealTimeGeoJsonActivity.kt"&#41; }})

In this example you will learn how to add a live GeoJSON source. We have set up a [lambda function](https://m6rgfvqjp34nnwqcdm4cmmy3cm0dtupu.lambda-url.us-east-1.on.aws/) that returns a new GeoJSON point every time it is called.

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="{{ s3_url&#40;"live_realtime_data_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"live_realtime_data.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (</figure>)

First we will create a `GeoJSONSource`.

```kotlin title="Adding GeoJSON source"
try {
    style.addSource(GeoJsonSource(ID_GEOJSON_SOURCE, URI(URL_GEOJSON_SOURCE)))
} catch (malformedUriException: URISyntaxException) {
    Timber.e(malformedUriException, "Invalid URL")
}
```

Next we will create a `SymbolLayer` that uses the source.

```kotlin title="Adding a SymbolLayer source"
val layer = SymbolLayer(ID_GEOJSON_LAYER, ID_GEOJSON_SOURCE)
layer.setProperties(
    PropertyFactory.iconImage("plane"),
    PropertyFactory.iconAllowOverlap(true)
)
style.addLayer(layer)
```

We use define a `Runnable` and use `android.os.Handler` with a `android.os.Looper` to update the GeoJSON source every 2 seconds.

```kotlin title="Defining a Runnable for updating the GeoJSON source"
private inner class RefreshGeoJsonRunnable(
    private val maplibreMap: MapLibreMap,
    private val handler: Handler
) : Runnable {
    override fun run() {
        val geoJsonSource = maplibreMap.style!!.getSource(ID_GEOJSON_SOURCE) as GeoJsonSource
        geoJsonSource.setUri(URL_GEOJSON_SOURCE)
        val features = geoJsonSource.querySourceFeatures(null)
        setIconRotation(features)
        handler.postDelayed(this, 2000)
    }
}
```

## Bonus: set icon rotation

You can set the icon rotation of the icon when ever the point is updated based on the last two points.

```kotlin title="Defining a Runnable for updating the GeoJSON source"
if (features.size != 1) {
    Timber.e("Expected only one feature")
    return
}

val feature = features[0]
val geometry = feature.geometry()
if (geometry !is Point) {
    Timber.e("Expected geometry to be a point")
    return
}

if (lastLocation == null) {
    lastLocation = geometry
    return
}

maplibreMap.style!!.getLayer(ID_GEOJSON_LAYER)!!.setProperties(
    PropertyFactory.iconRotate(calculateRotationAngle(lastLocation!!, geometry)),
)
```
