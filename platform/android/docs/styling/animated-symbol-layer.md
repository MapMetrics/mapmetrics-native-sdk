# Animated SymbolLayer

[//]: # ({{ activity_source_note&#40;"AnimatedSymbolLayerActivity.kt"&#41; }})

[//]: # (<figure markdown="span">)

[//]: # (  <video controls width="250" poster="{{ s3_url&#40;"animated_symbol_layer_thumbnail.jpg"&#41; }}" >)

[//]: # (    <source src="{{ s3_url&#40;"animated_symbol_layer.mp4"&#41; }}" />)

[//]: # (  </video>)

[//]: # (  {{ openmaptiles_caption&#40;&#41; }})

[//]: # (</figure>)


Notice that there are (red) cars randomly moving around, and a (yellow) taxi that is always heading to the passenger (indicated by the M symbol), which upon arrival hops to a different location again. We will focus on the passanger and the taxi, because the cars randomly moving around follow a similar pattern.

In a real application you would of course retrieve the locations from some sort of external API, but for the purposes of this example a random latitude longtitude pair within bounds of the currently visible screen will do.

```kotlin title="Getter method to get a random location on the screen"
private val latLngInBounds: LatLng
get() {
    val bounds = maplibreMap.projection.visibleRegion.latLngBounds
    val generator = Random()

    val randomLat = bounds.latitudeSouth + generator.nextDouble() * (bounds.latitudeNorth - bounds.latitudeSouth)
    val randomLon = bounds.longitudeWest + generator.nextDouble() * (bounds.longitudeEast - bounds.longitudeWest)

    return LatLng(randomLat, randomLon)
}
```

```kotlin title="Adding a passenger at a random location (on screen)"
private fun addPassenger(style: Style) {
    passenger = latLngInBounds
    val featureCollection = FeatureCollection.fromFeatures(
        arrayOf(
            Feature.fromGeometry(
                Point.fromLngLat(
                    passenger!!.longitude,
                    passenger!!.latitude
                )
            )
        )
    )
    style.addImage(
        PASSENGER,
        ResourcesCompat.getDrawable(resources, R.drawable.icon_burned, theme)!!
    )
    val geoJsonSource = GeoJsonSource(PASSENGER_SOURCE, featureCollection)
    style.addSource(geoJsonSource)
    val symbolLayer = SymbolLayer(PASSENGER_LAYER, PASSENGER_SOURCE)
    symbolLayer.withProperties(
        PropertyFactory.iconImage(PASSENGER),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAllowOverlap(true)
    )
    style.addLayerBelow(symbolLayer, RANDOM_CAR_LAYER)
}
```

Adding the taxi on screen is done very similarly.

```kotlin title="Adding the taxi with bearing"
private fun addTaxi(style: Style) {
    val latLng = latLngInBounds
    val properties = JsonObject()
    properties.addProperty(PROPERTY_BEARING, Car.getBearing(latLng, passenger))
    val feature = Feature.fromGeometry(
        Point.fromLngLat(
            latLng.longitude,
            latLng.latitude
        ),
        properties
    )
    val featureCollection = FeatureCollection.fromFeatures(arrayOf(feature))
    taxi = Car(feature, passenger, duration)
    style.addImage(
        TAXI,
        (ResourcesCompat.getDrawable(resources, R.drawable.ic_taxi_top, theme) as BitmapDrawable).bitmap
    )
    taxiSource = GeoJsonSource(TAXI_SOURCE, featureCollection)
    style.addSource(taxiSource!!)
    val symbolLayer = SymbolLayer(TAXI_LAYER, TAXI_SOURCE)
    symbolLayer.withProperties(
        PropertyFactory.iconImage(TAXI),
        PropertyFactory.iconRotate(Expression.get(PROPERTY_BEARING)),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true)
    )
    style.addLayer(symbolLayer)
}
```

For animating the taxi we use a [`ValueAnimator`](https://developer.android.com/reference/android/animation/ValueAnimator).

```kotlin title="Animate the taxi driving towards the passenger"
private fun animateTaxi(style: Style) {
    val valueAnimator = ValueAnimator.ofObject(LatLngEvaluator(), taxi!!.current, taxi!!.next)
    valueAnimator.addUpdateListener(object : AnimatorUpdateListener {
        private var latLng: LatLng? = null
        override fun onAnimationUpdate(animation: ValueAnimator) {
            latLng = animation.animatedValue as LatLng
            taxi!!.current = latLng
            updateTaxiSource()
        }
    })
    valueAnimator.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            updatePassenger(style)
            animateTaxi(style)
        }
    })
    valueAnimator.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator) {
            super.onAnimationStart(animation)
            taxi!!.feature.properties()!!
                .addProperty("bearing", Car.getBearing(taxi!!.current, taxi!!.next))
        }
    })
    valueAnimator.duration = (7 * taxi!!.current!!.distanceTo(taxi!!.next!!)).toLong()
    valueAnimator.interpolator = AccelerateDecelerateInterpolator()
    valueAnimator.start()
    animators.add(valueAnimator)
}
```
