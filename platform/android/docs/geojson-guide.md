# Using a GeoJSON Source

This guide will teach you how to use [`GeoJsonSource`](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/index.html) by deep diving into [GeoJSON](https://geojson.org/) file format.

## Goals

After finishing  this documentation you should be able to:

1. Understand how `Style`, `Layer`, and `Source` interact with each other.
2. Explore building blocks of GeoJSON data.
3. Use GeoJSON files in constructing `GeoJsonSource`s.
4. Update data at runtime.

## 1. Styles, Layers, and Data source

- A style defines the visual representation of the map such as colors and appearance.
- Layers control how data should be presented to the user.
- Data sources hold actual data and provides layers with it.

Styles consist of collections of layers and a data source. Layers reference data sources. Hence, they require a unique source ID when you construct them.
It would be meaningless if we don't have any data to show, so we need know how to supply data through a data source.

Firstly, we need to understand how to store data and pass it into a data source; therefore, we will discuss GeoJSON in the next session.

## 2. GeoJSON

[GeoJSON](https://geojson.org/) is a JSON file for encoding various geographical data structures.
It defines several JSON objects to represent geospatial information. Typicalle the`.geojson` extension is used for GeoJSON files.
We define the most fundamental objects:

- `Geometry` refers to a single geometric shape that contains one or more coordinates. These shapes are visual objects displayed on a map. A geometry can be one of the following six types:
    - Point
    - MultiPoint
    - LineString
    - MultilineString
    - Polygon
    - MultiPolygon
- `Feautue` is a compound object that combines a single geometry with user-defined attributes, such as name, color.
- `FeatureCollection` is set of features stored in an array. It is a root object that introduces all other features.

A typical GeoJSON structure might look like:

```json
{
  "type": "Feature",
  "geometry": {
    "type": "Point",
    "coordinates": [125.6, 10.1]
  },
  "properties": {
    "name": "Dinagat Islands"
  }
}
```

So far we learned describing geospatial data in GeoJSON files. We will start applying this knowledge into our map applications.

## 3. GeoJsonSource

As we discussed before, map requires some sort data to be rendered. We use different sources such as Vector, Raster and GeoJSON.
We will focus exclusively on `GeoJsonSource` and will not address other sources.

`GeoJsonSource` is a type of source that has a unique `String` ID and GeoJSON data.

There are several ways to construct a `GeoJsonSource`:

- Locally stored files such as assets and raw folders
- Remote services
- Raw string  parsed into FeatureCollections objects
- Geometry, Feature, and FeatureCollection objects that map to GeoJSON Base builders

A sample `GeoJsonSource`:

```kotlin
val source = GeoJsonSource("source", featureCollection)
val lineLayer = LineLayer("layer", "source")
  .withProperties(
    PropertyFactory.lineColor(Color.RED),
    PropertyFactory.lineWidth(10f)
  )

style.addSource(source)
style.addLayer(lineLayer)
```

Note that you can not simply show data on a map. Layers must reference them. Therefore, you create a layer that gives visual appearance to it.

### Creating GeoJSON sources

There are various ways you can create a `GeoJSONSource`. Some of the options are shown below.

```kotlin title="Loading from local files with assets folder file"
binding.mapView.getMapAsync { map ->
  map.moveCamera(CameraUpdateFactory.newLatLngZoom(cameraTarget, cameraZoom))
  map.setStyle(
    Style.Builder()
      .withImage(imageId, imageIcon)
      .withSource(GeoJsonSource(sourceId, URI("asset://points-sf.geojson")))
      .withLayer(SymbolLayer(layerId, sourceId).withProperties(iconImage(imageId)))
  )
}
```

```kotlin title="Loading with raw folder file"
val source: Source = try {
  GeoJsonSource("amsterdam-spots", ResourceUtils.readRawResource(this, R.raw.amsterdam))
} catch (ioException: IOException) {
  Toast.makeText(
    this@RuntimeStyleActivity,
    "Couldn't add source: " + ioException.message,
    Toast.LENGTH_SHORT
  ).show()
  return
}
maplibreMap.style!!.addSource(source)
var layer: FillLayer? = FillLayer("parksLayer", "amsterdam-spots")
layer!!.setProperties(
  PropertyFactory.fillColor(Color.RED),
  PropertyFactory.fillOutlineColor(Color.BLUE),
  PropertyFactory.fillOpacity(0.3f),
  PropertyFactory.fillAntialias(true)
)
```

```kotlin title="Parsing inline JSON"
fun readRawResource(context: Context?, @RawRes rawResource: Int): String {
  var json = ""
  if (context != null) {
    val writer: Writer = StringWriter()
    val buffer = CharArray(1024)
    context.resources.openRawResource(rawResource).use { `is` ->
      val reader: Reader = BufferedReader(InputStreamReader(`is`, "UTF-8"))
      var numRead: Int
      while (reader.read(buffer).also { numRead = it } != -1) {
        writer.write(buffer, 0, numRead)
      }
    }
    json = writer.toString()
  }
  return json
}
```

```kotlin title="Loading from remote services"
private fun createEarthquakeSource(): GeoJsonSource {
  return GeoJsonSource(EARTHQUAKE_SOURCE_ID, URI(EARTHQUAKE_SOURCE_URL))
}
```

```kotlin
companion object {
  private const val EARTHQUAKE_SOURCE_URL =
    "https://maplibre.org/maplibre-gl-js/docs/assets/earthquakes.geojson"
  private const val EARTHQUAKE_SOURCE_ID = "earthquakes"
  private const val HEATMAP_LAYER_ID = "earthquakes-heat"
  private const val HEATMAP_LAYER_SOURCE = "earthquakes"
  private const val CIRCLE_LAYER_ID = "earthquakes-circle"
}
```

```kotlin title="Parsing string with the fromJson method of FeatureCollection"
return FeatureCollection.fromJson(
  """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "type": "Feature",
          "properties": {},
          "geometry": {
            "type": "Polygon",
            "coordinates": [
              [
                [
                  -77.06867337226866,
                  38.90467655551809
                ],
                [
                  -77.06233263015747,
                  38.90479344272695
                ],
                [
                  -77.06234335899353,
                  38.906463238984344
                ],
                [
                  -77.06290125846863,
                  38.907206285691615
                ],
                [
                  -77.06364154815674,
                  38.90684728656818
                ],
                [
                  -77.06326603889465,
                  38.90637140121084
                ],
                [
                  -77.06321239471436,
                  38.905561553883246
                ],
                [
                  -77.0691454410553,
                  38.905436318935635
                ],
                [
                  -77.06912398338318,
                  38.90466820642439
                ],
                [
                  -77.06867337226866,
                  38.90467655551809
                ]
              ]
            ]
          }
        }
      ]
    }
    """.trimIndent()
).features()!![0].geometry() as Polygon
```

```kotlin title="Creating Geometry, Feature, and FeatureCollections from scratch"
val properties = JsonObject()
properties.addProperty("key1", "value1")
val source = GeoJsonSource(
  "test-source",
  FeatureCollection.fromFeatures(
    arrayOf(
      Feature.fromGeometry(Point.fromLngLat(17.1, 51.0), properties),
      Feature.fromGeometry(Point.fromLngLat(17.2, 51.0), properties),
      Feature.fromGeometry(Point.fromLngLat(17.3, 51.0), properties),
      Feature.fromGeometry(Point.fromLngLat(17.4, 51.0), properties)
    )
  )
)
style.addSource(source)
val visible = Expression.eq(Expression.get("key1"), Expression.literal("value1"))
val invisible = Expression.neq(Expression.get("key1"), Expression.literal("value1"))
val layer = CircleLayer("test-layer", source.id)
  .withFilter(visible)
style.addLayer(layer)
```

Note that the GeoJSON objects we discussed earlier have classes defined in the MapLibre SDK.
Therefore, we can either map JSON objects to regular Java/Kotlin objects or build them directly.

## 4. Updating data at runtime

The key feature of `GeoJsonSource`s is that once we add one, we can set another set of data.
We achieve this using `setGeoJson()` method. For instance, we create a source variable and check if we have not assigned it, then we create a new source object and add it to style; otherwise, we set a different data source:

```kotlin
private fun createFeatureCollection(): FeatureCollection {
  val point = if (isInitialPosition) {
    Point.fromLngLat(-74.01618140, 40.701745)
  } else {
    Point.fromLngLat(-73.988097, 40.749864)
  }
  val properties = JsonObject()
  properties.addProperty(KEY_PROPERTY_SELECTED, isSelected)
  val feature = Feature.fromGeometry(point, properties)
  return FeatureCollection.fromFeatures(arrayOf(feature))
}
```

```kotlin
private fun updateSource(style: Style?) {
  val featureCollection = createFeatureCollection()
  if (source != null) {
    source!!.setGeoJson(featureCollection)
  } else {
    source = GeoJsonSource(SOURCE_ID, featureCollection)
    style!!.addSource(source!!)
  }
}
```

See [this guide](styling/animated-symbol-layer.md) for an advanced example that showcases random cars and a passenger on a map updating their positions with smooth animation.

## Summary

GeoJsonSources have their pros and cons. They are most effective when you want to add additional data to your style or provide features like animating objects on your map.

However, working with large datasets can be challenging if you need to manipulate and store data within the app; in such cases, it’s better to use a remote data source.
