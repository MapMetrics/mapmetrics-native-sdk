# Vector Tiles

[//]: # ({{ activity_source_note&#40;"VectorTileActivity.kt"&#41; }})

You can specify where to load MVTs (which sometimes have `.pbf` extension) by creating a `TileSet` object with template parameters (for example `{z}` which will be replaced with the zoom level).

MapLibre has [a repo](https://github.com/maplibre/demotiles/tree/gh-pages/tiles-omt) with some example vector tiles with the OpenMapTiles schema around Innsbruck, Austria. In the example we load these MVTs and create a line layer for the road network.

```kotlin
val tileset = TileSet(
    "openmaptiles",
    "https://demotiles.maplibre.org/tiles-omt/{z}/{x}/{y}.pbf"
)
val openmaptiles = VectorSource("openmaptiles", tileset)
style.addSource(openmaptiles)
val roadLayer = LineLayer("road", "openmaptiles").apply {
    setSourceLayer("transportation")
    setProperties(
        lineColor("red"),
        lineWidth(2.0f)
    )
}
```

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of road overlay from vector tile source]&#40;{{ s3_url&#40;"vectortilesource.png"&#41; }}&#41;{ width="400" })

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)
