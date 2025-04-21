package org.maplibre.android.testapp.activity.maplayout

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.maps.*
import org.maplibre.android.testapp.R
import org.maplibre.android.testapp.utils.ApiKeyUtils
import org.maplibre.android.testapp.utils.NavUtils

/**
 * Test activity showcasing a simple MapView without any MapLibreMap interaction.
 */
class SimpleMapActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // activity uses singleInstance for testing purposes
                // code below provides a default navigation when using the app
                NavUtils.navigateHome(this@SimpleMapActivity)
            }
        })
        setContentView(R.layout.activity_map_simple)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync {
            val key = ApiKeyUtils.getApiKey(applicationContext)
            if (key == null || key == "YOUR_API_KEY_GOES_HERE") {
                it.setStyle(
                    Style.Builder().fromUri("https://gateway.mapmetrics.org/styles/?fileName=facc61a1-d7f6-4ad5-9b80-580949f35509/jim.json&token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJmYWNjNjFhMS1kN2Y2LTRhZDUtOWI4MC01ODA5NDlmMzU1MDkiLCJzY29wZSI6WyJtYXBzIiwic2VhcmNoIl0sImlhdCI6MTc0NDc5MDQzOX0.kIuOVdSqr6ifYnNkrt6b2I11ySlW96H9Gg_E1UpQ_ck")
                )
            } else {
                val styles = Style.getPredefinedStyles()
                if (styles.isNotEmpty()) {
                    val styleUrl = styles[0].url
                    it.setStyle(Style.Builder().fromUri(styleUrl))
                }
            }
        }
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

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                // activity uses singleInstance for testing purposes
                // code below provides a default navigation when using the app
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}