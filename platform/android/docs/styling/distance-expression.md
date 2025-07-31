# Distance Expression

[//]: # ({{ activity_source_note&#40;"DistanceExpressionActivity.kt"&#41; }})

This example shows how you can modify a style to only show certain features within a certain distance to a point. For this the [distance expression](https://maplibre.org/maplibre-style-spec/expressions/#within) is used.

[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of map where only labels inside some circular area are shown]&#40;{{ s3_url&#40;"distance_expression_activity.png"&#41; }}&#41;{ width="400" })

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)

First we add a [fill layer](https://maplibre.org/maplibre-style-spec/layers/#fill) and a GeoJSON source.

```kotlin
val center = Point.fromLngLat(lon, lat)
val circle = TurfTransformation.circle(center, 150.0, TurfConstants.UNIT_METRES)
maplibreMap.setStyle(
    Style.Builder()
        .fromUri(TestStyles.OPENFREEMAP_BRIGHT)
        .withSources(
            GeoJsonSource(
                POINT_ID,
                Point.fromLngLat(lon, lat)
            ),
            GeoJsonSource(CIRCLE_ID, circle)
        )
        .withLayerBelow(
            FillLayer(CIRCLE_ID, CIRCLE_ID)
                .withProperties(
                    fillOpacity(0.5f),
                    fillColor(Color.parseColor("#3bb2d0"))
                ),
            "poi"
        )

)
```

Next, we only show features from symbol layers that are less than a certain distance from the point. All symbol layers whose identifier does not start with `poi` are completely hidden.

```kotlin
for (layer in style.layers) {
    if (layer is SymbolLayer) {
        if (layer.id.startsWith("poi")) {
            layer.setFilter(lt(
                distance(
                    Point.fromLngLat(lon, lat)
                ),
                150
            ))
        } else {
            layer.setProperties(visibility(NONE))
        }
    }
}
```
