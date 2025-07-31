# Circle Layer (with Clustering)

[//]: # ({{ activity_source_note&#40;"CircleLayerActivity.kt"&#41; }})

In this example we will add a circle layer for a GeoJSON source. We also show how you can use the [cluster](https://maplibre.org/maplibre-style-spec/sources/#cluster) property of a GeoJSON source.

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="400" poster="{{ s3_url&#40;"circle_layer_cluster_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"circle_layer_cluster.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (</figure>)

Create a `GeoJsonSource` instance, pass a unique identifier for the source and the URL where the GeoJSON is available. Next add the source to the style.

```kotlin title="Setting up the GeoJSON source"
try {
    source = GeoJsonSource(SOURCE_ID, URI(URL_BUS_ROUTES))
} catch (exception: URISyntaxException) {
    Timber.e(exception, "That's not an url... ")
}
style.addSource(source!!)
```

Now you can create a `CircleLayer`, pass it a unique identifier for the layer and the source identifier of the GeoJSON source just created. You can use a `PropertyFactory` to pass [circle layer properties](https://maplibre.org/maplibre-style-spec/layers/#circle). Lastly add the layer to your style.

```kotlin title="Create circle layer a small orange circle for each bus stop"
layer = CircleLayer(LAYER_ID, SOURCE_ID)
layer!!.setProperties(
    PropertyFactory.circleColor(Color.parseColor("#FF9800")),
    PropertyFactory.circleRadius(2.0f)
)
style.addLayer(layer!!)
```

## Clustering

Next we will show you how you can use clustering. Create a `GeoJsonSource` as before, but with some additional options to enable clustering.

```kotlin title="Setting up the clustered GeoJSON source"
style.addSource(
    GeoJsonSource(
        SOURCE_ID_CLUSTER,
        URI(URL_BUS_ROUTES),
        GeoJsonOptions()
            .withCluster(true)
            .withClusterMaxZoom(14)
            .withClusterRadius(50)
    )
)
```

When enabling clustering some [special attributes](https://maplibre.org/maplibre-style-spec/sources/#cluster) will be available to the points in the newly created layer. One is `cluster`, which is true if the point indicates a cluster. We want to show a bus stop for points that are **not** clustered.

```kotlin title="Add a symbol layers for points that are not clustered"
val unclustered = SymbolLayer("unclustered-points", SOURCE_ID_CLUSTER)
unclustered.setProperties(
    PropertyFactory.iconImage("bus-icon"),
)
unclustered.setFilter(
    Expression.neq(Expression.get("cluster"), true)
)
style.addLayer(unclustered)
```

Next we define which point amounts correspond to which colors. More than 150 points will get a red circle, clusters with 21-150 points will be green and clusters with 20 or less points will be green.

```kotlin title="Define different colors for different point amounts"
val layers = arrayOf(
    150 to ResourcesCompat.getColor(
        resources,
        R.color.redAccent,
        theme
    ),
    20 to ResourcesCompat.getColor(resources, R.color.greenAccent, theme),
    0 to ResourcesCompat.getColor(
        resources,
        R.color.blueAccent,
        theme
    )
)
```

Lastly we iterate over the array of `Pair`s to create a `CircleLayer` for each element.

```kotlin title="Add different circle layers for clusters of different point amounts"
for (i in layers.indices) {
    // Add some nice circles
    val circles = CircleLayer("cluster-$i", SOURCE_ID_CLUSTER)
    circles.setProperties(
        PropertyFactory.circleColor(layers[i].second),
        PropertyFactory.circleRadius(18f)
    )

    val pointCount = Expression.toNumber(Expression.get("point_count"))
    circles.setFilter(
        if (i == 0) {
            Expression.all(
                Expression.has("point_count"),
                Expression.gte(
                    pointCount,
                    Expression.literal(layers[i].first)
                )
            )
        } else {
            Expression.all(
                Expression.has("point_count"),
                Expression.gt(
                    pointCount,
                    Expression.literal(layers[i].first)
                ),
                Expression.lt(
                    pointCount,
                    Expression.literal(layers[i - 1].first)
                )
            )
        }
    )

    style.addLayer(circles)
}
```
