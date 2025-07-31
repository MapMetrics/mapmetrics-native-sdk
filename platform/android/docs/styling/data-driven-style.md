# Data Driven Style

[//]: # ({{ activity_source_note&#40;"DataDrivenStyleActivity.kt"&#41; }})

In this example we will look at various types of data-driven styling.

The examples with 'Source' in the title apply data-driven styling the [parks of Amsterdam](https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroidTestApp/src/main/res/raw/amsterdam.geojson). Those examples often are based on the somewhat arbitrary `stroke-width` property part of the GeoJSON features. These examples are therefore most interesting to learn about the Kotlin API that can be used for data-driven styling.

!!! tip
    Refer to the [MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/) for more information about [expressions](https://maplibre.org/maplibre-style-spec/expressions/) such as [`interpolate`](https://maplibre.org/maplibre-style-spec/expressions/#interpolate) and [`step`](https://maplibre.org/maplibre-style-spec/expressions/#step).


## Exponential Zoom Function

```kotlin
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.interpolate(
            Expression.exponential(0.5f),
            Expression.zoom(),
            Expression.stop(1, Expression.color(Color.RED)),
            Expression.stop(5, Expression.color(Color.BLUE)),
            Expression.stop(10, Expression.color(Color.GREEN))
        )
    )
)
```

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="{{ s3_url&#40;"exponential_zoom_function_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"exponential_zoom_function.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (</figure>)


## Interval Zoom Function

```kotlin
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.step(
            Expression.zoom(),
            Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f),
            Expression.stop(1, Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f)),
            Expression.stop(5, Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f)),
            Expression.stop(10, Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f))
        )
    )
)
```

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="{{ s3_url&#40;"interval_zoom_function_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"interval_zoom_function.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (</figure>)

```json title="Equivalent JSON"
["step",["zoom"],["rgba",0.0,255.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0],5.0,["rgba",0.0,0.0,255.0,1.0],10.0,["rgba",0.0,255.0,0.0,1.0]]
```

## Exponential Source Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.interpolate(
            Expression.exponential(0.5f),
            Expression.get("stroke-width"),
            Expression.stop(1f, Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f)),
            Expression.stop(5f, Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f)),
            Expression.stop(10f, Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f))
        )
    )
)
```

## Categorical Source Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.match(
            Expression.get("name"),
            Expression.literal("Westerpark"),
            Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
            Expression.literal("Jordaan"),
            Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
            Expression.literal("Prinseneiland"),
            Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f),
            Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f)
        )
    )
)
```

## Identity Source Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillOpacity(
        Expression.get("fill-opacity")
    )
)
```

## Interval Source Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.step(
            Expression.get("stroke-width"),
            Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f),
            Expression.stop(1f, Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f)),
            Expression.stop(2f, Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f)),
            Expression.stop(3f, Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f))
        )
    )
)
```

## Composite Exponential Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.interpolate(
            Expression.exponential(1f),
            Expression.zoom(),
            Expression.stop(
                12,
                Expression.step(
                    Expression.get("stroke-width"),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f),
                    Expression.stop(1f, Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f)),
                    Expression.stop(2f, Expression.rgba(0.0f, 0.0f, 0.0f, 1.0f)),
                    Expression.stop(3f, Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f))
                )
            ),
            Expression.stop(
                15,
                Expression.step(
                    Expression.get("stroke-width"),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f),
                    Expression.stop(1f, Expression.rgba(255.0f, 255.0f, 0.0f, 1.0f)),
                    Expression.stop(2f, Expression.rgba(211.0f, 211.0f, 211.0f, 1.0f)),
                    Expression.stop(3f, Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f))
                )
            ),
            Expression.stop(
                18,
                Expression.step(
                    Expression.get("stroke-width"),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f),
                    Expression.stop(1f, Expression.rgba(0.0f, 0.0f, 0.0f, 1.0f)),
                    Expression.stop(2f, Expression.rgba(128.0f, 128.0f, 128.0f, 1.0f)),
                    Expression.stop(3f, Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f))
                )
            )
        )
    )
)
```

## Identity Source Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillOpacity(
        Expression.get("fill-opacity")
    )
)
```

## Composite Interval Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.step(
            Expression.zoom(),
            Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f),
            Expression.stop(
                7f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                8f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                9f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                10f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                11f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                12f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                13f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                14f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.literal("Jordaan"),
                    Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f),
                    Expression.literal("PrinsenEiland"),
                    Expression.rgba(0.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                15f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                16f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                17f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                18f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.literal("Jordaan"),
                    Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                19f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                20f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                21f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                22f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            )
        )
    )
)
```

## Composite Categorical Function

```kotlin
val layer = maplibreMap.style!!.getLayerAs<FillLayer>(AMSTERDAM_PARKS_LAYER)!!
layer.setProperties(
    PropertyFactory.fillColor(
        Expression.step(
            Expression.zoom(),
            Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f),
            Expression.stop(
                7f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                8f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                9f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                10f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                11f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                12f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                13f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                14f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.literal("Jordaan"),
                    Expression.rgba(0.0f, 255.0f, 0.0f, 1.0f),
                    Expression.literal("PrinsenEiland"),
                    Expression.rgba(0.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                15f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                16f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                17f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                18f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.literal("Jordaan"),
                    Expression.rgba(0.0f, 255.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                19f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                20f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                21f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(255.0f, 0.0f, 0.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            ),
            Expression.stop(
                22f,
                Expression.match(
                    Expression.get("name"),
                    Expression.literal("Westerpark"),
                    Expression.rgba(0.0f, 0.0f, 255.0f, 1.0f),
                    Expression.rgba(255.0f, 255.0f, 255.0f, 1.0f)
                )
            )
        )
    )
)
```
