package org.maplibre.android.testapp.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Global configuration manager for MapMetrics styles
 * This allows external applications to configure tokens and style URLs
 */
object MapMetricsConfig {
    private const val PREFS_NAME = "mapmetrics_config"
    private const val KEY_TOKEN = "mapmetrics_token"
    private const val KEY_STYLE_FILENAME = "mapmetrics_style_filename"
    private const val KEY_BASE_URL = "mapmetrics_base_url"
    
    // Default values
    private const val DEFAULT_BASE_URL = "https://gateway.mapmetrics-atlas.net/styles/"
    private const val DEFAULT_STYLE_FILENAME = "dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json"
    private const val DEFAULT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJkZDUwODgyMi05NTAyLTRhYjUtYmZlMi01ZTZlZDU4MDljMmQiLCJzY29wZSI6WyJtYXBzIl0sImlhdCI6MTc1MzQ0MjMzOH0.TogFJJb58kA7QP2664xA3g5tIEZGcX8mNHVkRBlHLBM"

    private var sharedPreferences: SharedPreferences? = null
    
    /**
     * Initialize the configuration with application context
     * Call this in your Application.onCreate()
     */
    fun initialize(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Set the MapMetrics authentication token
     * @param token Your MapMetrics token
     */
    fun setToken(token: String) {
        sharedPreferences?.edit()?.putString(KEY_TOKEN, token)?.apply()
    }
    
    /**
     * Get the MapMetrics authentication token
     * @return The configured token or default placeholder
     */
    fun getToken(): String {
        return sharedPreferences?.getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN
    }
    
    /**
     * Set the MapMetrics style filename
     * @param filename The style filename (e.g., "dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json")
     */
    fun setStyleFilename(filename: String) {
        sharedPreferences?.edit()?.putString(KEY_STYLE_FILENAME, filename)?.apply()
    }
    
    /**
     * Get the MapMetrics style filename
     * @return The configured filename or default
     */
    fun getStyleFilename(): String {
        return sharedPreferences?.getString(KEY_STYLE_FILENAME, DEFAULT_STYLE_FILENAME) ?: DEFAULT_STYLE_FILENAME
    }
    
    /**
     * Set the MapMetrics base URL
     * @param baseUrl The base URL for MapMetrics styles
     */
    fun setBaseUrl(baseUrl: String) {
        sharedPreferences?.edit()?.putString(KEY_BASE_URL, baseUrl)?.apply()
    }
    
    /**
     * Get the MapMetrics base URL
     * @return The configured base URL or default
     */
    fun getBaseUrl(): String {
        return sharedPreferences?.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }
    
    /**
     * Build the complete MapMetrics style URL with current configuration
     * @return Complete style URL with token
     */
    fun getStyleUrl(): String {
        val baseUrl = getBaseUrl()
        val filename = getStyleFilename()
        val token = getToken()
        return "$baseUrl?fileName=$filename&token=$token"
    }
    
    /**
     * Check if a valid token is configured (not the default placeholder)
     * @return true if a real token is configured
     */
    fun hasValidToken(): Boolean {
        val token = getToken()
        return token != DEFAULT_TOKEN && token.isNotEmpty()
    }
    
    /**
     * Clear all configuration (useful for testing)
     */
    fun clear() {
        sharedPreferences?.edit()?.clear()?.apply()
    }
} 