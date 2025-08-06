package org.maplibre.android.maps

import android.graphics.Color
import android.view.Gravity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.geometry.LatLng
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.*

@RunWith(RobolectricTestRunner::class)
class MapMetricsMapOptionsTest {
    @Test
    fun testSanity() {
        Assert.assertNotNull("should not be null",
            MapMetricsMapOptions()
        )
    }

    @Test
    fun testDebugEnabled() {
        Assert.assertFalse(MapMetricsMapOptions().debugActive)
        Assert.assertTrue(MapMetricsMapOptions().debugActive(true).debugActive)
        Assert.assertFalse(MapMetricsMapOptions().debugActive(false).debugActive)
    }

    @Test
    fun testCompassEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().compassEnabled(true).compassEnabled)
        Assert.assertFalse(MapMetricsMapOptions().compassEnabled(false).compassEnabled)
    }

    @Test
    fun testCompassGravity() {
        Assert.assertEquals(
            Gravity.TOP or Gravity.END,
            MapMetricsMapOptions().compassGravity
        )
        Assert.assertEquals(
            Gravity.BOTTOM,
            MapMetricsMapOptions().compassGravity(Gravity.BOTTOM).compassGravity
        )
        Assert.assertNotEquals(
            Gravity.START.toLong(),
            MapMetricsMapOptions().compassGravity(Gravity.BOTTOM).compassGravity.toLong()
        )
    }

    @Test
    fun testCompassMargins() {
        Assert.assertTrue(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .compassMargins(intArrayOf(0, 1, 2, 3)).compassMargins
            )
        )
        Assert.assertFalse(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .compassMargins(intArrayOf(0, 0, 0, 0)).compassMargins
            )
        )
    }

    @Test
    fun testLogoEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().logoEnabled(true).logoEnabled)
        Assert.assertFalse(MapMetricsMapOptions().logoEnabled(false).logoEnabled)
    }

    @Test
    fun testLogoGravity() {
        Assert.assertEquals(
            Gravity.BOTTOM or Gravity.START,
            MapMetricsMapOptions().logoGravity
        )
        Assert.assertEquals(
            Gravity.BOTTOM,
            MapMetricsMapOptions().logoGravity(Gravity.BOTTOM).logoGravity
        )
        Assert.assertNotEquals(
            Gravity.START.toLong(),
            MapMetricsMapOptions().logoGravity(Gravity.BOTTOM).logoGravity.toLong()
        )
    }

    @Test
    fun testLogoMargins() {
        Assert.assertTrue(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .logoMargins(intArrayOf(0, 1, 2, 3)).logoMargins
            )
        )
        Assert.assertFalse(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .logoMargins(intArrayOf(0, 0, 0, 0)).logoMargins
            )
        )
    }

    @Test
    fun testAttributionTintColor() {
        Assert.assertEquals(-1, MapMetricsMapOptions().attributionTintColor)
        Assert.assertEquals(
            Color.RED,
            MapMetricsMapOptions().attributionTintColor(Color.RED).attributionTintColor
        )
    }

    @Test
    fun testAttributionEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().attributionEnabled(true).attributionEnabled)
        Assert.assertFalse(MapMetricsMapOptions().attributionEnabled(false).attributionEnabled)
    }

    @Test
    fun testAttributionGravity() {
        Assert.assertEquals(
            Gravity.BOTTOM or Gravity.START,
            MapMetricsMapOptions().attributionGravity
        )
        Assert.assertEquals(
            Gravity.BOTTOM,
            MapMetricsMapOptions().attributionGravity(Gravity.BOTTOM).attributionGravity
        )
        Assert.assertNotEquals(
            Gravity.START.toLong(),
            MapMetricsMapOptions().attributionGravity(Gravity.BOTTOM).attributionGravity.toLong()
        )
    }

    @Test
    fun testAttributionMargins() {
        Assert.assertTrue(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .attributionMargins(intArrayOf(0, 1, 2, 3)).attributionMargins
            )
        )
        Assert.assertFalse(
            Arrays.equals(
                intArrayOf(0, 1, 2, 3),
                MapMetricsMapOptions()
                    .attributionMargins(intArrayOf(0, 0, 0, 0)).attributionMargins
            )
        )
    }

    @Test
    fun testMinZoom() {
        Assert.assertEquals(
            MapLibreConstants.MINIMUM_ZOOM.toDouble(),
            MapMetricsMapOptions().minZoomPreference,
            DELTA
        )
        Assert.assertEquals(
            5.0,
            MapMetricsMapOptions().minZoomPreference(5.0).minZoomPreference,
            DELTA
        )
        Assert.assertNotEquals(
            2.0,
            MapMetricsMapOptions().minZoomPreference(5.0).minZoomPreference,
            DELTA
        )
    }

    @Test
    fun testMaxZoom() {
        Assert.assertEquals(
            MapLibreConstants.MAXIMUM_ZOOM.toDouble(),
            MapMetricsMapOptions().maxZoomPreference,
            DELTA
        )
        Assert.assertEquals(
            5.0,
            MapMetricsMapOptions().maxZoomPreference(5.0).maxZoomPreference,
            DELTA
        )
        Assert.assertNotEquals(
            2.0,
            MapMetricsMapOptions().maxZoomPreference(5.0).maxZoomPreference,
            DELTA
        )
    }

    @Test
    fun testMinPitch() {
        Assert.assertEquals(
            MapLibreConstants.MINIMUM_PITCH.toDouble(),
            MapMetricsMapOptions().minPitchPreference,
            DELTA
        )
        Assert.assertEquals(
            5.0,
            MapMetricsMapOptions().minPitchPreference(5.0).minPitchPreference,
            DELTA
        )
        Assert.assertNotEquals(
            2.0,
            MapMetricsMapOptions().minPitchPreference(5.0).minPitchPreference,
            DELTA
        )
    }

    @Test
    fun testMaxPitch() {
        Assert.assertEquals(
            MapLibreConstants.MAXIMUM_PITCH.toDouble(),
            MapMetricsMapOptions().maxPitchPreference,
            DELTA
        )
        Assert.assertEquals(
            5.0,
            MapMetricsMapOptions().maxPitchPreference(5.0).maxPitchPreference,
            DELTA
        )
        Assert.assertNotEquals(
            2.0,
            MapMetricsMapOptions().maxPitchPreference(5.0).maxPitchPreference,
            DELTA
        )
    }

    @Test
    fun testTiltGesturesEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().tiltGesturesEnabled)
        Assert.assertTrue(MapMetricsMapOptions().tiltGesturesEnabled(true).tiltGesturesEnabled)
        Assert.assertFalse(MapMetricsMapOptions().tiltGesturesEnabled(false).tiltGesturesEnabled)
    }

    @Test
    fun testScrollGesturesEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().scrollGesturesEnabled)
        Assert.assertTrue(MapMetricsMapOptions().scrollGesturesEnabled(true).scrollGesturesEnabled)
        Assert.assertFalse(MapMetricsMapOptions().scrollGesturesEnabled(false).scrollGesturesEnabled)
    }

    @Test
    fun testHorizontalScrollGesturesEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().horizontalScrollGesturesEnabled)
        Assert.assertTrue(MapMetricsMapOptions().horizontalScrollGesturesEnabled(true).horizontalScrollGesturesEnabled)
        Assert.assertFalse(MapMetricsMapOptions().horizontalScrollGesturesEnabled(false).horizontalScrollGesturesEnabled)
    }

    @Test
    fun testZoomGesturesEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().zoomGesturesEnabled)
        Assert.assertTrue(MapMetricsMapOptions().zoomGesturesEnabled(true).zoomGesturesEnabled)
        Assert.assertFalse(MapMetricsMapOptions().zoomGesturesEnabled(false).zoomGesturesEnabled)
    }

    @Test
    fun testRotateGesturesEnabled() {
        Assert.assertTrue(MapMetricsMapOptions().rotateGesturesEnabled)
        Assert.assertTrue(MapMetricsMapOptions().rotateGesturesEnabled(true).rotateGesturesEnabled)
        Assert.assertFalse(MapMetricsMapOptions().rotateGesturesEnabled(false).rotateGesturesEnabled)
    }

    @Test
    fun testCamera() {
        val position = CameraPosition.Builder().build()
        Assert.assertEquals(
            CameraPosition.Builder(position).build(),
            MapMetricsMapOptions().camera(position).camera
        )
        Assert.assertNotEquals(
            CameraPosition.Builder().target(LatLng(1.0, 1.0)),
            MapMetricsMapOptions().camera(position)
        )
        Assert.assertNull(MapMetricsMapOptions().camera)
    }

    @Test
    fun testPrefetchesTiles() {
        // Default value
        Assert.assertTrue(MapMetricsMapOptions().prefetchesTiles)

        // Check mutations
        Assert.assertTrue(MapMetricsMapOptions().setPrefetchesTiles(true).prefetchesTiles)
        Assert.assertFalse(MapMetricsMapOptions().setPrefetchesTiles(false).prefetchesTiles)
    }

    @Test
    fun testPrefetchZoomDelta() {
        // Default value
        Assert.assertEquals(4, MapMetricsMapOptions().prefetchZoomDelta)

        // Check mutations
        Assert.assertEquals(
            5,
            MapMetricsMapOptions().setPrefetchZoomDelta(5).prefetchZoomDelta
        )
    }

    @Test
    fun testCrossSourceCollisions() {
        // Default value
        Assert.assertTrue(MapMetricsMapOptions().crossSourceCollisions)

        // check mutations
        Assert.assertTrue(MapMetricsMapOptions().crossSourceCollisions(true).crossSourceCollisions)
        Assert.assertFalse(MapMetricsMapOptions().crossSourceCollisions(false).crossSourceCollisions)
    }

    @Test
    fun testLocalIdeographFontFamily_enabledByDefault() {
        val options = MapMetricsMapOptions.createFromAttributes(RuntimeEnvironment.application, null)
        Assert.assertEquals(
            MapLibreConstants.DEFAULT_FONT,
            options.localIdeographFontFamily
        )
    }

    companion object {
        private const val DELTA = 1e-15
    }
}
