# Animated Image Source

[//]: # ({{ activity_source_note&#40;"AnimatedImageSourceActivity.kt"&#41; }})

In this example we will see how we can animate an image source. This is the MapLibre Native equivalent of [this MapLibre GL JS example](https://maplibre.org/maplibre-gl-js/docs/examples/animate-images/).

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="400" poster="{{ s3_url&#40;"animated_image_source_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"animated_image_source.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)

We set up an [image source](https://maplibre.org/maplibre-style-spec/sources/#image) in a particular quad. Then we kick of a runnable that periodically updates the image source.

```kotlin title="Creating the image source"
val quad = LatLngQuad(
    LatLng(46.437, -80.425),
    LatLng(46.437, -71.516),
    LatLng(37.936, -71.516),
    LatLng(37.936, -80.425)
)
val imageSource = ImageSource(ID_IMAGE_SOURCE, quad, R.drawable.southeast_radar_0)
val layer = RasterLayer(ID_IMAGE_LAYER, ID_IMAGE_SOURCE)
map.setStyle(
    Style.Builder()
        .fromUri(TestStyles.AMERICANA)
        .withSource(imageSource)
        .withLayer(layer)
) { style: Style? ->
    runnable = RefreshImageRunnable(imageSource, handler)
    runnable?.let {
        handler.postDelayed(it, 100)
    }
}
```

```kotlin title="Updating the image source"
imageSource.setImage(drawables[drawableIndex++]!!)
if (drawableIndex > 3) {
    drawableIndex = 0
}
handler.postDelayed(this, 1000)
```
