# Draggable Marker

[//]: # ({{ activity_source_note&#40;"DraggableMarkerActivity.kt"&#41; }})

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="400" poster="{{ s3_url&#40;"draggable_marker_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"draggable_marker.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (</figure>)

## Adding a marker on tap

```kotlin title="Adding a tap listener to the map to add a marker on tap"
maplibreMap.addOnMapClickListener {
      // Adding a marker on map click
      val features = maplibreMap.queryRenderedSymbols(it, layerId)
      if (features.isEmpty()) {
            addMarker(it)
      } else {
            // Displaying marker info on marker click
            Snackbar.make(
                  mapView,
                  "Marker's position: %.4f, %.4f".format(it.latitude, it.longitude),
                  Snackbar.LENGTH_LONG
            )
                  .show()
      }

      false
}
```

## Allowing markers to be dragged

This is slightly more involved, as we implement it by implementing a `DraggableSymbolsManager` helper class.

This class is initialized and we pass a few callbacks when when markers are start or end being dragged.

```kotlin
draggableSymbolsManager = DraggableSymbolsManager(
      mapView,
      maplibreMap,
      featureCollection,
      source,
      layerId,
      actionBarHeight,
      0
)

// Adding symbol drag listeners
draggableSymbolsManager?.addOnSymbolDragListener(object : DraggableSymbolsManager.OnSymbolDragListener {
      override fun onSymbolDragStarted(id: String) {
            binding.draggedMarkerPositionTv.visibility = View.VISIBLE
            Snackbar.make(
                  mapView,
                  "Marker drag started (%s)".format(id),
                  Snackbar.LENGTH_SHORT
            )
                  .show()
      }

      override fun onSymbolDrag(id: String) {
            val point = featureCollection.features()?.find {
                  it.id() == id
            }?.geometry() as Point
            binding.draggedMarkerPositionTv.text = "Dragged marker's position: %.4f, %.4f".format(point.latitude(), point.longitude())
      }

      override fun onSymbolDragFinished(id: String) {
            binding.draggedMarkerPositionTv.visibility = View.GONE
            Snackbar.make(
                  mapView,
                  "Marker drag finished (%s)".format(id),
                  Snackbar.LENGTH_SHORT
            )
                  .show()
      }
})
```

The implementation of `DraggableSymbolsManager` follows. In its initializer we define a handler for when a user long taps on a marker. This then starts dragging that marker. It does this by temporarily suspending all other gestures.

We create a custom implementation of `MoveGestureDetector.OnMoveGestureListener` and pass this to an instance of `AndroidGesturesManager` linked to the map view.

!!! tip
      See [maplibre-gestures-android](https://github.com/maplibre/maplibre-gestures-android) for the implementation details of the gestures library used by MapLibre Android.

```kotlin
/**
 * A manager, that allows dragging symbols after they are long clicked.
 * Since this manager lives outside of the Maps SDK, we need to intercept parent's motion events
 * and pass them with [DraggableSymbolsManager.onParentTouchEvent].
 * If we were to try and overwrite [AppCompatActivity.onTouchEvent], those events would've been
 * consumed by the map.
 *
 * We also need to setup a [DraggableSymbolsManager.androidGesturesManager],
 * because after disabling map's gestures and starting the drag process
 * we still need to listen for move gesture events which map won't be able to provide anymore.
 *
 * @param mapView the mapView
 * @param maplibreMap the maplibreMap
 * @param symbolsCollection the collection that contains all the symbols that we want to be draggable
 * @param symbolsSource the source that contains the [symbolsCollection]
 * @param symbolsLayerId the ID of the layer that the symbols are displayed on
 * @param touchAreaShiftX X-axis padding that is applied to the parent's window motion event,
 * as that window can be bigger than the [mapView].
 * @param touchAreaShiftY Y-axis padding that is applied to the parent's window motion event,
 * as that window can be bigger than the [mapView].
 * @param touchAreaMaxX maximum value of X-axis motion event
 * @param touchAreaMaxY maximum value of Y-axis motion event
 */
class DraggableSymbolsManager(
      mapView: MapView,
      private val maplibreMap: MapLibreMap,
      private val symbolsCollection: FeatureCollection,
      private val symbolsSource: GeoJsonSource,
      private val symbolsLayerId: String,
      private val touchAreaShiftY: Int = 0,
      private val touchAreaShiftX: Int = 0,
      private val touchAreaMaxX: Int = mapView.width,
      private val touchAreaMaxY: Int = mapView.height
) {

      private val androidGesturesManager: AndroidGesturesManager = AndroidGesturesManager(mapView.context, false)
      private var draggedSymbolId: String? = null
      private val onSymbolDragListeners: MutableList<OnSymbolDragListener> = mutableListOf()

      init {
            maplibreMap.addOnMapLongClickListener {
                  // Starting the drag process on long click
                  draggedSymbolId = maplibreMap.queryRenderedSymbols(it, symbolsLayerId).firstOrNull()?.id()?.also { id ->
                        maplibreMap.uiSettings.setAllGesturesEnabled(false)
                        maplibreMap.gesturesManager.moveGestureDetector.interrupt()
                        notifyOnSymbolDragListeners {
                              onSymbolDragStarted(id)
                        }
                  }
                  false
            }

            androidGesturesManager.setMoveGestureListener(MyMoveGestureListener())
      }

      inner class MyMoveGestureListener : MoveGestureDetector.OnMoveGestureListener {
            override fun onMoveBegin(detector: MoveGestureDetector): Boolean {
                  return true
            }

            override fun onMove(detector: MoveGestureDetector, distanceX: Float, distanceY: Float): Boolean {
                  if (detector.pointersCount > 1) {
                        // Stopping the drag when we don't work with a simple, on-pointer move anymore
                        stopDragging()
                        return true
                  }

                  // Updating symbol's position
                  draggedSymbolId?.also { draggedSymbolId ->
                        val moveObject = detector.getMoveObject(0)
                        val point = PointF(moveObject.currentX - touchAreaShiftX, moveObject.currentY - touchAreaShiftY)

                        if (point.x < 0 || point.y < 0 || point.x > touchAreaMaxX || point.y > touchAreaMaxY) {
                              stopDragging()
                        }

                        val latLng = maplibreMap.projection.fromScreenLocation(point)

                        symbolsCollection.features()?.indexOfFirst {
                              it.id() == draggedSymbolId
                        }?.also { index ->
                              symbolsCollection.features()?.get(index)?.also { oldFeature ->
                                    val properties = oldFeature.properties()
                                    val newFeature = Feature.fromGeometry(
                                          Point.fromLngLat(latLng.longitude, latLng.latitude),
                                          properties,
                                          draggedSymbolId
                                    )
                                    symbolsCollection.features()?.set(index, newFeature)
                                    symbolsSource.setGeoJson(symbolsCollection)
                                    notifyOnSymbolDragListeners {
                                          onSymbolDrag(draggedSymbolId)
                                    }
                                    return true
                              }
                        }
                  }

                  return false
            }

            override fun onMoveEnd(detector: MoveGestureDetector, velocityX: Float, velocityY: Float) {
                  // Stopping the drag when move ends
                  stopDragging()
            }
      }

      private fun stopDragging() {
            maplibreMap.uiSettings.setAllGesturesEnabled(true)
            draggedSymbolId?.let {
                  notifyOnSymbolDragListeners {
                        onSymbolDragFinished(it)
                  }
            }
            draggedSymbolId = null
      }

      fun onParentTouchEvent(ev: MotionEvent?) {
            androidGesturesManager.onTouchEvent(ev)
      }

      private fun notifyOnSymbolDragListeners(action: OnSymbolDragListener.() -> Unit) {
            onSymbolDragListeners.forEach(action)
      }

      fun addOnSymbolDragListener(listener: OnSymbolDragListener) {
            onSymbolDragListeners.add(listener)
      }

      fun removeOnSymbolDragListener(listener: OnSymbolDragListener) {
            onSymbolDragListeners.remove(listener)
      }

      interface OnSymbolDragListener {
            fun onSymbolDragStarted(id: String)
            fun onSymbolDrag(id: String)
            fun onSymbolDragFinished(id: String)
      }
}
```
