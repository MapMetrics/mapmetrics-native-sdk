# Building Layer

[//]: # ({{ activity_source_note&#40;"BuildingFillExtrusionActivity.kt"&#41; }})

In this example will show how to add a [Fill Extrusion](https://maplibre.org/maplibre-style-spec/layers/#fill-extrusion) layer to a style.

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="400" poster="{{ s3_url&#40;"building_layer_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"building_layer.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)

We use the [OpenFreeMap Bright](https://openfreemap.org/quick_start/) style which, unlike OpenFreeMap Libery, does not have a fill extrusion layer by default. However, if you inspect this style with [Maputnik](https://maplibre.org/maputnik) you will find that the multipolygons in the  `building` layer (of the `openfreemap` source) each have `render_min_height` and `render_height` properties.

```kotlin title="Setting up the fill extrusion layer"
val fillExtrusionLayer = FillExtrusionLayer("building-3d", "openmaptiles")
fillExtrusionLayer.sourceLayer = "building"
fillExtrusionLayer.setFilter(
    Expression.all(
        Expression.has("render_height"),
        Expression.has("render_min_height")
    )
)
fillExtrusionLayer.minZoom = 15f
fillExtrusionLayer.setProperties(
    PropertyFactory.fillExtrusionColor(Color.LTGRAY),
    PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
    PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
    PropertyFactory.fillExtrusionOpacity(0.9f)
)
style.addLayer(fillExtrusionLayer)
```

```kotlin title="Changing the light direction"
isInitPosition = !isInitPosition
if (isInitPosition) {
    light!!.position = Position(1.5f, 90f, 80f)
} else {
    light!!.position = Position(1.15f, 210f, 30f)
}
```

```kotlin title="Changing the light color"
isRedColor = !isRedColor
light!!.setColor(ColorUtils.colorToRgbaString(if (isRedColor) Color.RED else Color.BLUE))
```
