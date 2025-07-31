# Zoom Methods

[//]: # ({{ activity_source_note&#40;"ManualZoomActivity.kt"&#41; }})

[//]: # (This example shows different methods of zooming in.)

[//]: # ()
[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/zoom_methods_thumbnail.jpg">)

[//]: # (    <source src="https://dwxvn1oqw6mkc.cloudfront.net/android-documentation-resources/zoom_methods.mp4" />)

[//]: # (  </video>)

[//]: # (</figure>)

Each method uses `MapLibreMap.animateCamera`, but with a different `CameraUpdateFactory`.

#### Zooming In

```kotlin
maplibreMap.animateCamera(CameraUpdateFactory.zoomIn())
```

#### Zooming Out

```kotlin
maplibreMap.animateCamera(CameraUpdateFactory.zoomOut())
```

#### Zoom By Some Amount of Zoom Levels

```kotlin
maplibreMap.animateCamera(CameraUpdateFactory.zoomBy(2.0))
```

#### Zoom to a Zoom Level

```kotlin
maplibreMap.animateCamera(CameraUpdateFactory.zoomTo(2.0))
```

#### Zoom to a Point

```kotlin
val view = window.decorView
maplibreMap.animateCamera(
    CameraUpdateFactory.zoomBy(
        1.0,
        Point(view.measuredWidth / 4, view.measuredHeight / 4)
    )
)
```
