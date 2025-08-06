package org.maplibre.android.testapp.activity.camera

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapMetrics
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapMetricsMap
import org.maplibre.android.maps.MapMetricsMap.CancelableCallback
import org.maplibre.android.maps.MapMetricsMap.OnCameraIdleListener
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.styles.TestStyles
import timber.log.Timber

/**
 * Test activity showcasing the Camera API and listen to camera updates by animating the camera
 * above London.
 *
 * Shows how to use animate, ease and move camera update factory methods.
 */
class CameraAnimationTypeActivity : AppCompatActivity(), OnMapReadyCallback {
    // # --8<-- [start:callback]
    private val callback: CancelableCallback =
        object : CancelableCallback {
            override fun onCancel() {
                Timber.i("Duration onCancel Callback called.")
                Toast.makeText(
                    applicationContext,
                    "Ease onCancel Callback called.",
                    Toast.LENGTH_LONG
                )
                    .show()
            }

            override fun onFinish() {
                Timber.i("Duration onFinish Callback called.")
                Toast.makeText(
                    applicationContext,
                    "Ease onFinish Callback called.",
                    Toast.LENGTH_LONG
                )
                    .show()
            }
        }
    // # --8<-- [end:callback]

    private lateinit var mapMetricsMap: MapMetricsMap
    private lateinit var mapView: MapView
    private var cameraState = false
    private val cameraIdleListener = OnCameraIdleListener {
        if (this::mapMetricsMap.isInitialized) {
            Timber.w(mapMetricsMap.cameraPosition.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_animation_types)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        MapMetrics.initializeSessionWithToken(applicationContext , "") {
            mapView.getMapAsync(this)
        }
    }

    override fun onMapReady(map: MapMetricsMap) {
        mapMetricsMap = map

        mapMetricsMap.setStyle(TestStyles.getMapMetricsStyle())

        mapMetricsMap.uiSettings.isAttributionEnabled = false
        mapMetricsMap.uiSettings.isLogoEnabled = false
        mapMetricsMap.addOnCameraIdleListener(cameraIdleListener)

        // handle move button clicks
        val moveButton = findViewById<View>(R.id.cameraMoveButton)
        moveButton?.setOnClickListener { view: View? ->
            // # --8<-- [start:moveCamera]
            val cameraPosition =
                CameraPosition.Builder()
                    .target(nextLatLng)
                    .zoom(14.0)
                    .tilt(30.0)
                    .tilt(0.0)
                    .build()
            mapMetricsMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            // # --8<-- [end:moveCamera]
        }

        // handle ease button clicks
        val easeButton = findViewById<View>(R.id.cameraEaseButton)
        easeButton?.setOnClickListener { view: View? ->
            // # --8<-- [start:easeCamera]
            val cameraPosition =
                CameraPosition.Builder()
                    .target(nextLatLng)
                    .zoom(15.0)
                    .bearing(180.0)
                    .tilt(30.0)
                    .build()
            mapMetricsMap.easeCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition),
                7500,
                callback
            )
            // # --8<-- [end:easeCamera]
        }

        // handle animate button clicks
        val animateButton = findViewById<View>(R.id.cameraAnimateButton)
        animateButton?.setOnClickListener { view: View? ->
            // # --8<-- [start:animateCamera]
            val cameraPosition =
                CameraPosition.Builder().target(nextLatLng).bearing(270.0).tilt(20.0).build()
            mapMetricsMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition),
                7500,
                callback
            )
            // # --8<-- [end:animateCamera]
        }
    }

    private val nextLatLng: LatLng
        get() {
            cameraState = !cameraState
            return if (cameraState) LAT_LNG_TOWER_BRIDGE else LAT_LNG_LONDON_EYE
        }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::mapMetricsMap.isInitialized) {
            mapMetricsMap.removeOnCameraIdleListener(cameraIdleListener)
        }
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    companion object {
        private val LAT_LNG_LONDON_EYE = LatLng(51.50325, -0.11968)
        private val LAT_LNG_TOWER_BRIDGE = LatLng(51.50550, -0.07520)
    }
}
