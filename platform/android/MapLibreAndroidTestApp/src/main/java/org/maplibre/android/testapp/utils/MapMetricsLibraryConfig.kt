package org.maplibre.android.testapp.utils

/**
 * Public API for configuring MapMetrics library settings
 * This is the interface that external applications will use to configure the library
 */
object MapMetricsLibraryConfig {
    
    /**
     * Configure MapMetrics with your authentication token
     * Call this before initializing any maps
     * 
     * @param token Your MapMetrics authentication token
     */
    fun configureToken(token: String) {
        MapMetricsConfig.setToken(token)
    }
    
    /**
     * Configure MapMetrics with custom style filename
     * 
     * @param filename The style filename (e.g., "dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json")
     */
    fun configureStyleFilename(filename: String) {
        MapMetricsConfig.setStyleFilename(filename)
    }
    
    /**
     * Configure MapMetrics with custom base URL
     * 
     * @param baseUrl The base URL for MapMetrics styles
     */
    fun configureBaseUrl(baseUrl: String) {
        MapMetricsConfig.setBaseUrl(baseUrl)
    }
    
    /**
     * Configure all MapMetrics settings at once
     * 
     * @param token Your MapMetrics authentication token
     * @param filename The style filename (optional, uses default if null)
     * @param baseUrl The base URL (optional, uses default if null)
     */
    fun configure(token: String, filename: String? = null, baseUrl: String? = null) {
        configureToken(token)
        filename?.let { configureStyleFilename(it) }
        baseUrl?.let { configureBaseUrl(it) }
    }
    
    /**
     * Check if a valid token is configured
     * 
     * @return true if a real token is configured (not the default placeholder)
     */
    fun hasValidConfiguration(): Boolean {
        return MapMetricsConfig.hasValidToken()
    }
    
    /**
     * Get the current style URL (for debugging)
     * 
     * @return The complete style URL with current configuration
     */
    fun getCurrentStyleUrl(): String {
        return MapMetricsConfig.getStyleUrl()
    }
    
    /**
     * Clear all configuration (useful for testing)
     */
    fun clearConfiguration() {
        MapMetricsConfig.clear()
    }
} 