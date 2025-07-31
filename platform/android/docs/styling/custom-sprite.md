# Add Custom Sprite

[//]: # ({{ activity_source_note&#40;"CustomSpriteActivity.kt"&#41; }})

This example showcases adding a sprite image and using it in a Symbol Layer.

```kotlin
// Add an icon to reference later
style.addImage(
    CUSTOM_ICON,
    BitmapFactory.decodeResource(
        resources,
        R.drawable.ic_car_top
    )
)

// Add a source with a geojson point
point = Point.fromLngLat(13.400972, 52.519003)
source = GeoJsonSource(
    "point",
    FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(point)))
)
maplibreMap.style!!.addSource(source!!)

// Add a symbol layer that references that point source
layer = SymbolLayer("layer", "point")
layer.setProperties( // Set the id of the sprite to use
    PropertyFactory.iconImage(CUSTOM_ICON),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true)
)

// lets add a circle below labels!
maplibreMap.style!!.addLayerBelow(layer, "water-intermittent")
fab.setImageResource(R.drawable.ic_directions_car_black)
```
