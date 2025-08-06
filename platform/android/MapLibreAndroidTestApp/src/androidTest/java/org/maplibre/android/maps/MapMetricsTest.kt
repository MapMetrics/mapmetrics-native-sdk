package org.maplibre.android.maps

import androidx.test.annotation.UiThreadTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.AppCenter
import org.maplibre.android.MapMetrics
import org.maplibre.android.exceptions.MapLibreConfigurationException

@RunWith(AndroidJUnit4ClassRunner::class)
class MapMetricsTest : AppCenter() {
    private var realToken: String? = null
    @Before
    fun setup() {
        realToken = MapMetrics.getApiKey()
    }

    @Test
    @UiThreadTest
    fun testConnected() {
        Assert.assertTrue(MapMetrics.isConnected())

        // test manual connectivity
        MapMetrics.setConnected(true)
        Assert.assertTrue(MapMetrics.isConnected())
        MapMetrics.setConnected(false)
        Assert.assertFalse(MapMetrics.isConnected())

        // reset to Android connectivity
        MapMetrics.setConnected(null)
        Assert.assertTrue(MapMetrics.isConnected())
    }

    @Test
    @UiThreadTest
    fun setApiKey() {
        MapMetrics.setApiKey(API_KEY)
        Assert.assertSame(API_KEY, MapMetrics.getApiKey())
        MapMetrics.setApiKey(API_KEY_2)
        Assert.assertSame(API_KEY_2, MapMetrics.getApiKey())
    }

    @Test
    @UiThreadTest
    fun setNullApiKey() {
        Assert.assertThrows(
            MapLibreConfigurationException::class.java
        ) { MapMetrics.setApiKey(null) }
    }

    @After
    fun tearDown() {
        if (realToken?.isNotEmpty() == true) {
            MapMetrics.setApiKey(realToken)
        }

    }

    companion object {
        private const val API_KEY = "pk.0000000001"
        private const val API_KEY_2 = "pk.0000000002"
    }
}
