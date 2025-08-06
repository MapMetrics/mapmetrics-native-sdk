# Using the Snapshotter

This guide will help you walk through how to use [MapSnapshotter](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.snapshotter/-map-snapshotter/index.html).

## Map Snapshot with Local Style

[//]: # ({{ activity_source_note&#40;"MapSnapshotterLocalStyleActivity.kt"&#41; }})

[//]: # (To get started we will show how to use the map snapshotter with a local style.)

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![Map Snapshotter with Local Style]&#40;https://github.com/user-attachments/assets/897452c6-52e3-4e58-828c-4f7366b3ba90&#41;{ width="300" })

[//]: # (</figure>)

Add the [source code of the Demotiles style](https://github.com/maplibre/demotiles/blob/gh-pages/style.json) as `demotiles.json` to the `res/raw` directory of our app[^1]. First we will read this style:

[^1]: See [App resources overview](https://developer.android.com/guide/topics/resources/providing-resources) for this and other ways you can provide resources to your app.

```kotlin
val styleJson = resources.openRawResource(R.raw.demotiles).reader().readText()
```

Next, we configure the MapSnapshotter, passing height and width, the style we just read and the camera position:

```kotlin
mapSnapshotter = MapSnapshotter(
    applicationContext,
    MapSnapshotter.Options(
        container.measuredWidth.coerceAtMost(1024),
        container.measuredHeight.coerceAtMost(1024)
    )
        .withStyleBuilder(Style.Builder().fromJson(styleJson))
        .withCameraPosition(
            CameraPosition.Builder().target(LatLng(LATITUDE, LONGITUDE))
                .zoom(ZOOM).build()
        )
)
```

Lastly we use the `.start()` method to create the snapshot, and pass callbacks for when the snapshot is ready or for when an error occurs.

```kotlin
mapSnapshotter.start({ snapshot ->
    Timber.i("Snapshot ready")
    val imageView = findViewById<View>(R.id.snapshot_image) as ImageView
    imageView.setImageBitmap(snapshot.bitmap)
}) { error -> Timber.e(error )}
```

## Show a Grid of Snapshots

[//]: # ({{ activity_source_note&#40;"MapSnapshotterActivity.kt"&#41; }})

In this example, we demonstrate how to use the `MapSnapshotter` to create multiple map snapshots with different styles and camera positions, displaying them in a grid layout.

[//]: # (<figure markdown="span">)

[//]: # (  ![Map Snapshotter]&#40;https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/map_snapshotter.png&#41;{ width="300" })

[//]: # (</figure>)

First we create a [`GridLayout`](https://developer.android.com/reference/kotlin/android/widget/GridLayout) and a list of `MapSnapshotter` instances. We create a `Style.Builder` with a different style for each cell in the grid.

```kotlin
val styles = arrayOf(
    TestStyles.DEMOTILES,
    TestStyles.AMERICANA,
    TestStyles.OPENFREEMAP_LIBERTY,
    TestStyles.AWS_OPEN_DATA_STANDARD_LIGHT,
    TestStyles.PROTOMAPS_LIGHT,
    TestStyles.PROTOMAPS_DARK,
    TestStyles.PROTOMAPS_WHITE,
    TestStyles.PROTOMAPS_GRAYSCALE
)
val builder = Style.Builder().fromUri(
    styles[(row * grid.rowCount + column) % styles.size]
)
```

Next we create a `MapSnapshotter.Options` object to customize the settings of each snapshot(ter).

```kotlin
val options = MapSnapshotter.Options(
    grid.measuredWidth / grid.columnCount,
    grid.measuredHeight / grid.rowCount
)
    .withPixelRatio(1f)
    .withLocalIdeographFontFamily(MapLibreConstants.DEFAULT_FONT)
```

For some rows we randomize the visible region of the snapshot:

```kotlin
if (row % 2 == 0) {
    options.withRegion(
        LatLngBounds.Builder()
            .include(
                LatLng(
                    randomInRange(-80f, 80f).toDouble(),
                    randomInRange(-160f, 160f).toDouble()
                )
            )
            .include(
                LatLng(
                    randomInRange(-80f, 80f).toDouble(),
                    randomInRange(-160f, 160f).toDouble()
                )
            )
            .build()
    )
}
```

For some columns we randomize the camera position:

```kotlin
if (column % 2 == 0) {
    options.withCameraPosition(
        CameraPosition.Builder()
            .target(
                options.region?.center ?: LatLng(
                    randomInRange(-80f, 80f).toDouble(),
                    randomInRange(-160f, 160f).toDouble()
                )
            )
            .bearing(randomInRange(0f, 360f).toDouble())
            .tilt(randomInRange(0f, 60f).toDouble())
            .zoom(randomInRange(0f, 10f).toDouble())
            .padding(1.0, 1.0, 1.0, 1.0)
            .build()
    )
}
```

In the last column of the first row we add two bitmaps. See the next example for more details.

```kotlin
if (row == 0 && column == 2) {
    val carBitmap = BitmapUtils.getBitmapFromDrawable(
        ResourcesCompat.getDrawable(resources, R.drawable.ic_directions_car_black, theme)
    )

    // Marker source
    val markerCollection = FeatureCollection.fromFeatures(
        arrayOf(
            Feature.fromGeometry(
                Point.fromLngLat(4.91638, 52.35673),
                featureProperties("1", "Android")
            ),
            Feature.fromGeometry(
                Point.fromLngLat(4.91638, 12.34673),
                featureProperties("2", "Car")
            )
        )
    )
    val markerSource: Source = GeoJsonSource(MARKER_SOURCE, markerCollection)

    // Marker layer
    val markerSymbolLayer = SymbolLayer(MARKER_LAYER, MARKER_SOURCE)
        .withProperties(
            PropertyFactory.iconImage(Expression.get(TITLE_FEATURE_PROPERTY)),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(
                Expression.switchCase(
                    Expression.toBool(Expression.get(SELECTED_FEATURE_PROPERTY)),
                    Expression.literal(1.5f),
                    Expression.literal(1.0f)
                )
            ),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconColor(Color.BLUE)
        )
    builder.withImage("Car", Objects.requireNonNull(carBitmap!!), false)
        .withSources(markerSource)
        .withLayers(markerSymbolLayer)
    options
        .withRegion(null)
        .withCameraPosition(
            CameraPosition.Builder()
                .target(
                    LatLng(5.537109374999999, 52.07950600379697)
                )
                .zoom(1.0)
                .padding(1.0, 1.0, 1.0, 1.0)
                .build()
        )
}
```

## Map Snapshot with Bitmap Overlay

[//]: # ({{ activity_source_note&#40;"MapSnapshotterBitMapOverlayActivity.kt"&#41; }})

This example adds a bitmap on top of the snapshot. It also demonstrates how you can add a click listener to a snapshot.

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of Map Snapshot with Bitmap Overlay]&#40;https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/map_snapshot_with_bitmap_overlay.png&#41;{ width="300" })

[//]: # (</figure>)


```kotlin title="MapSnapshotterBitMapOverlayActivity.kt"
package org.maplibre.android.testapp.activity.snapshot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.styles.TestStyles
import timber.log.Timber

/**
 * Test activity showing how to use a the [MapSnapshotter] and overlay
 * [android.graphics.Bitmap]s on top.
 */
class MapSnapshotterBitMapOverlayActivity :
    AppCompatActivity(),
    MapSnapshotter.SnapshotReadyCallback {
    private var mapSnapshotter: MapSnapshotter? = null

    @get:VisibleForTesting
    var mapSnapshot: MapSnapshot? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_snapshotter_marker)
        val container = findViewById<View>(R.id.container)
        container.viewTreeObserver
            .addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    Timber.i("Starting snapshot")
                    mapSnapshotter = MapSnapshotter(
                        applicationContext,
                        MapSnapshotter.Options(
                            Math.min(container.measuredWidth, 1024),
                            Math.min(container.measuredHeight, 1024)
                        )
                            .withStyleBuilder(
                                Style.Builder().fromUri(TestStyles.AMERICANA)
                            )
                            .withCameraPosition(
                                CameraPosition.Builder().target(LatLng(52.090737, 5.121420))
                                    .zoom(15.0).build()
                            )
                    )
                    mapSnapshotter!!.start(this@MapSnapshotterBitMapOverlayActivity)
                }
            })
    }

    override fun onStop() {
        super.onStop()
        mapSnapshotter!!.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onSnapshotReady(snapshot: MapSnapshot) {
        mapSnapshot = snapshot
        Timber.i("Snapshot ready")
        val imageView = findViewById<View>(R.id.snapshot_image) as ImageView
        val image = addMarker(snapshot)
        imageView.setImageBitmap(image)
        imageView.setOnTouchListener { v: View?, event: MotionEvent ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val latLng = snapshot.latLngForPixel(PointF(event.x, event.y))
                Timber.e("Clicked LatLng is %s", latLng)
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun addMarker(snapshot: MapSnapshot): Bitmap {
        val canvas = Canvas(snapshot.bitmap)
        val marker =
            BitmapFactory.decodeResource(resources, R.drawable.maplibre_marker_icon_default, null)
        // Dom toren
        val markerLocation = snapshot.pixelForLatLng(LatLng(52.090649433011315, 5.121310651302338))
        canvas.drawBitmap(
            marker, /* Subtract half of the width so we center the bitmap correctly */
            markerLocation.x - marker.width / 2, /* Subtract half of the height so we align the bitmap bottom correctly */
            markerLocation.y - marker.height / 2,
            null
        )
        return snapshot.bitmap
    }
}
```

## Map Snapshotter with Heatmap Layer

[//]: # ({{ activity_source_note&#40;"MapSnapshotterHeatMapActivity.kt"&#41; }})

In this example, we demonstrate how to use the `MapSnapshotter` to create a snapshot of a map that includes a heatmap layer. The heatmap represents earthquake data loaded from a GeoJSON source.

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of Snapshotter with Heatmap]&#40;https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/snapshotter_headmap_screenshot.png&#41;{ width="300" })

[//]: # (</figure>)

First, we create the `MapSnapshotterHeatMapActivity` class, which extends `AppCompatActivity` and implements `MapSnapshotter.SnapshotReadyCallback` to receive the snapshot once it's ready.

```kotlin
class MapSnapshotterHeatMapActivity : AppCompatActivity(), MapSnapshotter.SnapshotReadyCallback 
```

In the `onCreate` method, we set up the layout and initialize the `MapSnapshotter` once the layout is ready.

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_map_snapshotter_marker)
    val container = findViewById<View>(R.id.container)
    container.viewTreeObserver
        .addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                Timber.i("Starting snapshot")
                val builder = Style.Builder().fromUri(TestStyles.AMERICANA)
                    .withSource(earthquakeSource!!)
                    .withLayerAbove(heatmapLayer, "water")
                mapSnapshotter = MapSnapshotter(
                    applicationContext,
                    MapSnapshotter.Options(container.measuredWidth, container.measuredHeight)
                        .withStyleBuilder(builder)
                        .withCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(15.0, (-94).toDouble()))
                                .zoom(5.0)
                                .padding(1.0, 1.0, 1.0, 1.0)
                                .build()
                        )
                )
                mapSnapshotter!!.start(this@MapSnapshotterHeatMapActivity)
            }
        })
}
```

Here, we wait for the layout to be laid out using an `OnGlobalLayoutListener` before initializing the `MapSnapshotter`. We create a `Style.Builder` with a base style (`TestStyles.AMERICANA`), add the earthquake data source, and add the heatmap layer above the "water" layer.

The `heatmapLayer` property defines the `HeatmapLayer` used to visualize the earthquake data.

```kotlin
private val heatmapLayer: HeatmapLayer
get() {
    val layer = HeatmapLayer(HEATMAP_LAYER_ID, EARTHQUAKE_SOURCE_ID)
    layer.maxZoom = 9f
    layer.sourceLayer = HEATMAP_LAYER_SOURCE
    layer.setProperties(
        PropertyFactory.heatmapColor(
            Expression.interpolate(
                Expression.linear(), Expression.heatmapDensity(),
                Expression.literal(0), Expression.rgba(33, 102, 172, 0),
                Expression.literal(0.2), Expression.rgb(103, 169, 207),
                Expression.literal(0.4), Expression.rgb(209, 229, 240),
                Expression.literal(0.6), Expression.rgb(253, 219, 199),
                Expression.literal(0.8), Expression.rgb(239, 138, 98),
                Expression.literal(1), Expression.rgb(178, 24, 43)
            )
        ),
        PropertyFactory.heatmapWeight(
            Expression.interpolate(
                Expression.linear(),
                Expression.get("mag"),
                Expression.stop(0, 0),
                Expression.stop(6, 1)
            )
        ),
        PropertyFactory.heatmapIntensity(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(0, 1),
                Expression.stop(9, 3)
            )
        ),
        PropertyFactory.heatmapRadius(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(0, 2),
                Expression.stop(9, 20)
            )
        ),
        PropertyFactory.heatmapOpacity(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(7, 1),
                Expression.stop(9, 0)
            )
        )
    )
    return layer
}
```

This code sets up the heatmap layer's properties, such as color ramp, weight, intensity, radius, and opacity, using expressions that interpolate based on data properties and zoom level.

We also define the `earthquakeSource`, which loads data from a GeoJSON file containing earthquake information.

```kotlin
private val earthquakeSource: Source?
get() {
    var source: Source? = null
    try {
        source = GeoJsonSource(EARTHQUAKE_SOURCE_ID, URI(EARTHQUAKE_SOURCE_URL))
    } catch (uriSyntaxException: URISyntaxException) {
        Timber.e(uriSyntaxException, "That's not a valid URL.")
    }
    return source
}
```

When the snapshot is ready, the `onSnapshotReady` callback is invoked, where we set the snapshot bitmap to an `ImageView` to display it.

```kotlin
@SuppressLint("ClickableViewAccessibility")
override fun onSnapshotReady(snapshot: MapSnapshot) {
    Timber.i("Snapshot ready")
    val imageView = findViewById<ImageView>(R.id.snapshot_image)
    imageView.setImageBitmap(snapshot.bitmap)
}
```

Finally, we ensure to cancel the snapshotter in the `onStop` method to free up resources.

```kotlin
override fun onStop() {
    super.onStop()
    mapSnapshotter?.cancel()
}
```


## Map Snapshotter with Expression

[//]: # ({{ activity_source_note&#40;"MapSnapshotterWithinExpression.kt"&#41; }})

In this example the map on top is a live while the map on the bottom is a snapshot that is updated as you pan the map. We style of the snapshot is modified: using a [within](https://maplibre.org/maplibre-style-spec/expressions/#within) expression only POIs within a certain distance to a line is shown. A highlight for this area is added to the map as are various points.

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of Map Snapshot with Expression]&#40;https://github.com/user-attachments/assets/e75922ad-6115-4549-bcb7-7a40e03a81f4&#41;{ width="300" })

[//]: # (</figure>)

```kotlin title="MapSnapshotterWithinExpression.kt"
package org.maplibre.android.testapp.activity.turf

import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.geojson.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapMetricsMap
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression.within
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.NONE
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.testapp.databinding.ActivityMapsnapshotterWithinExpressionBinding
import org.maplibre.android.testapp.styles.TestStyles.getPredefinedStyleWithFallback

/**
 * An Activity that showcases the use of MapSnapshotter with 'within' expression
 */
class MapSnapshotterWithinExpression : AppCompatActivity() {
    private lateinit var binding: ActivityMapsnapshotterWithinExpressionBinding
    private lateinit var maplibreMap: MapLibreMap
    private lateinit var snapshotter: MapSnapshotter
    private var snapshotInProgress = false

    private val cameraListener = object : MapView.OnCameraDidChangeListener {
        override fun onCameraDidChange(animated: Boolean) {
            if (!snapshotInProgress) {
                snapshotInProgress = true
                snapshotter.setCameraPosition(maplibreMap.cameraPosition)
                snapshotter.start(object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        binding.imageView.setImageBitmap(snapshot.bitmap)
                        snapshotInProgress = false
                    }
                })
            }
        }
    }

    private val snapshotterObserver = object : MapSnapshotter.Observer {
        override fun onStyleImageMissing(imageName: String) {
        }

        override fun onDidFinishLoadingStyle() {
            // Show only POI labels inside geometry using within expression
            (snapshotter.getLayer("poi-label") as SymbolLayer).setFilter(
                within(
                    bufferLineStringGeometry()
                )
            )
            // Hide other types of labels to highlight POI labels
            (snapshotter.getLayer("road-label") as SymbolLayer).setProperties(visibility(NONE))
            (snapshotter.getLayer("transit-label") as SymbolLayer).setProperties(visibility(NONE))
            (snapshotter.getLayer("road-number-shield") as SymbolLayer).setProperties(visibility(NONE))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsnapshotterWithinExpressionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            maplibreMap = map

            // Setup camera position above Georgetown
            maplibreMap.cameraPosition = CameraPosition.Builder().target(LatLng(38.90628988399711, -77.06574689337494)).zoom(15.5).build()

            // Wait for the map to become idle before manipulating the style and camera of the map
            binding.mapView.addOnDidBecomeIdleListener(object : MapView.OnDidBecomeIdleListener {
                override fun onDidBecomeIdle() {
                    maplibreMap.easeCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().zoom(16.0).target(LatLng(38.905156245642814, -77.06535338052844)).bearing(80.68015859462369).tilt(55.0).build()
                        ),
                        1000
                    )
                    binding.mapView.removeOnDidBecomeIdleListener(this)
                }
            })
            // Load mapbox streets and add lines and circles
            setupStyle()
        }
    }

    private fun setupStyle() {
        // Assume the route is represented by an array of coordinates.
        val coordinates = listOf<Point>(
            Point.fromLngLat(-77.06866264343262, 38.90506061276737),
            Point.fromLngLat(-77.06283688545227, 38.905194197410545),
            Point.fromLngLat(-77.06285834312439, 38.906429843444094),
            Point.fromLngLat(-77.0630407333374, 38.90680554236621)
        )

        // Setup style with additional layers,
        // using streets as a base style
        maplibreMap.setStyle(
            Style.Builder().fromUri(getPredefinedStyleWithFallback("Streets"))
        ) {
            binding.mapView.addOnCameraDidChangeListener(cameraListener)
        }

        val options = MapSnapshotter.Options(binding.imageView.measuredWidth / 2, binding.imageView.measuredHeight / 2)
            .withCameraPosition(maplibreMap.cameraPosition)
            .withPixelRatio(2.0f).withStyleBuilder(
                Style.Builder().fromUri(getPredefinedStyleWithFallback("Streets")).withSources(
                    GeoJsonSource(
                        POINT_ID,
                        LineString.fromLngLats(coordinates)
                    ),
                    GeoJsonSource(
                        FILL_ID,
                        FeatureCollection.fromFeature(
                            Feature.fromGeometry(bufferLineStringGeometry())
                        ),
                        GeoJsonOptions().withBuffer(0).withTolerance(0.0f)
                    )
                ).withLayerBelow(
                    LineLayer(LINE_ID, POINT_ID).withProperties(
                        lineWidth(7.5f),
                        lineColor(Color.LTGRAY)
                    ),
                    "poi-label"
                ).withLayerBelow(
                    CircleLayer(POINT_ID, POINT_ID).withProperties(
                        circleRadius(7.5f),
                        circleColor(Color.DKGRAY),
                        circleOpacity(0.75f)
                    ),
                    "poi-label"
                ).withLayerBelow(
                    FillLayer(FILL_ID, FILL_ID).withProperties(
                        fillOpacity(0.12f),
                        fillColor(Color.YELLOW)
                    ),
                    LINE_ID
                )
            )
        snapshotter = MapSnapshotter(this, options)
        snapshotter.setObserver(snapshotterObserver)
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState)
        binding.mapView.onSaveInstanceState(outState)
    }
    private fun bufferLineStringGeometry(): Polygon {
        // TODO replace static data by Turf#Buffer: mapbox-java/issues/987
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
    }

    companion object {
        const val POINT_ID = "point"
        const val FILL_ID = "fill"
        const val LINE_ID = "line"
    }
}
```
