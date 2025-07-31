package org.maplibre.android.testapp

import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.text.TextUtils
import androidx.multidex.MultiDexApplication
import org.maplibre.android.MapStrictMode
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.log.Logger
import org.maplibre.android.testapp.utils.ApiKeyUtils
import org.maplibre.android.testapp.utils.TileLoadingMeasurementUtils
import org.maplibre.android.testapp.utils.TimberLogger
import org.maplibre.android.util.DefaultStyle
import org.maplibre.android.util.TileServerOptions
import timber.log.Timber
import timber.log.Timber.DebugTree

/**
 * Application class of the test application.
 *
 *
 * Initialises components as LeakCanary, Strictmode, Timber and MapLibre
 *
 */
open class MapLibreApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        initializeLogger()
        initializeStrictMode()
        initializeMapbox()
    }

    private fun initializeLogger() {
        Logger.setLoggerDefinition(TimberLogger())
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
    }

    private fun initializeStrictMode() {
        StrictMode.setThreadPolicy(
            ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )
    }

    private fun initializeMapbox() {
        val apiKey = ApiKeyUtils.getApiKey(applicationContext)
        if (apiKey != null) {
            validateApiKey(apiKey)
        }
        
        // Initialize with custom tile server options that include your custom style
        val customTileServerOptions = createCustomTileServerOptions()
        MapLibre.getInstance(applicationContext, apiKey, customTileServerOptions)
        
        // Initialize session with your custom token for cookie management
        MapLibre.initializeSessionWithToken(applicationContext, CUSTOM_STYLE_TOKEN) {
            Timber.d("MapLibre session initialized with custom token")
        }
        
        TileLoadingMeasurementUtils.setUpTileLoadingMeasurement()
        MapStrictMode.setStrictModeEnabled(true)
    }
    
    /**
     * Create custom TileServerOptions with your custom style as the default
     * This ensures your style is available globally throughout the application
     */
    private fun createCustomTileServerOptions(): TileServerOptions {
        // Create custom styles array with your style as the first (default) option
        val customStyles = arrayOf(
            CUSTOM_STYLE, // Your custom style as the first/default option
            // You can add more styles here if needed
            DefaultStyle("https://demotiles.maplibre.org/style.json", "MapLibre Demo", 1)
        )
        
        return TileServerOptions(
            baseURL = "https://gateway.mapmetrics-atlas.net/",
            uriSchemeAlias = "maplibre",
            sourceTemplate = "sources/{z}/{x}/{y}.pbf",
            sourceDomainName = "sources",
            sourceVersionPrefix = null,
            styleTemplate = "styles/?fileName={style}&token={token}",
            styleDomainName = "styles",
            styleVersionPrefix = null,
            spritesTemplate = "sprites/{style}",
            spritesDomainName = "sprites",
            spritesVersionPrefix = null,
            glyphsTemplate = "fonts/{fontstack}/{range}",
            glyphsDomainName = "fonts",
            glyphsVersionPrefix = null,
            tileTemplate = "tiles/{z}/{x}/{y}.pbf",
            tileDomainName = "tiles",
            tileVersionPrefix = null,
            apiKeyParameterName = "token",
            apiKeyRequired = true,
            defaultStyle = "MapMetrics Custom Style", // Set your style as default
            defaultStyles = customStyles
        )
    }

    companion object {
        val TILE_SERVER = WellKnownTileServer.MapLibre
        private const val DEFAULT_API_KEY = "YOUR_API_KEY_GOES_HERE"
        private const val API_KEY_NOT_SET_MESSAGE =
            (
                "In order to run the Test App you need to set a valid " +
                    "API key. During development, you can set the MLN_API_KEY environment variable for the SDK to " +
                    "automatically include it in the Test App. Otherwise, you can manually include it in the " +
                    "res/values/developer-config.xml file in the MapLibreAndroidTestApp folder."
                )
        
        // Global custom style configuration - Replace with your actual values
        private const val CUSTOM_STYLE_BASE_URL = "https://gateway.mapmetrics-atlas.net/styles/"
        private const val CUSTOM_STYLE_FILENAME = "dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json"
        private const val CUSTOM_STYLE_TOKEN = "YOUR_TOKEN_HERE" // Replace with your actual token
        
        // Build the complete default style URL
        private val DEFAULT_STYLE_URL: String
            get() = "$CUSTOM_STYLE_BASE_URL?fileName=$CUSTOM_STYLE_FILENAME&token=$CUSTOM_STYLE_TOKEN"
        
        // Create custom style for global use
        private val CUSTOM_STYLE = DefaultStyle(
            DEFAULT_STYLE_URL,
            "MapMetrics Custom Style",
            1
        )

        private fun validateApiKey(apiKey: String) {
            if (TextUtils.isEmpty(apiKey) || apiKey == DEFAULT_API_KEY) {
                Timber.e(API_KEY_NOT_SET_MESSAGE)
            }
        }
    }
}
