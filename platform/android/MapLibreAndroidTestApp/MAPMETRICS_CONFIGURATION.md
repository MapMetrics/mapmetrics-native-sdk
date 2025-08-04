# MapMetrics Library Configuration

This document explains how to configure MapMetrics styles in the MapLibre library.

## Overview

The MapMetrics configuration system allows external applications to configure authentication tokens and style URLs without modifying the library code. This makes the library reusable across different projects with different MapMetrics configurations.

## Quick Start

### 1. Configure in Your Application

In your `Application.onCreate()` method, configure MapMetrics before initializing any maps:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configure MapMetrics with your token
        MapMetricsLibraryConfig.configure(
            token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
            filename = "dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json",
            baseUrl = "https://gateway.mapmetrics-atlas.net/styles/"
        )
        
        // Initialize MapLibre
        MapLibre.getInstance(this, apiKey)
    }
}
```

### 2. Use in Your Activities

Now you can use MapMetrics styles in any activity:

```kotlin
class MyMapActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mapView.getMapAsync { map ->
            // Use MapMetrics style
            map.setStyle(Style.Builder().fromUri(TestStyles.MAPATLAS))
            
            // Or use the method
            map.setStyle(Style.Builder().fromUri(TestStyles.getMapMetricsStyle()))
        }
    }
}
```

## Configuration Options

### Basic Configuration

```kotlin
// Just set the token (uses default filename and base URL)
MapMetricsLibraryConfig.configureToken("your-token-here")
```

### Advanced Configuration

```kotlin
// Configure everything
MapMetricsLibraryConfig.configure(
    token = "your-token-here",
    filename = "your-custom-style.json",
    baseUrl = "https://your-custom-domain.com/styles/"
)
```

### Individual Configuration

```kotlin
// Configure each setting separately
MapMetricsLibraryConfig.configureToken("your-token-here")
MapMetricsLibraryConfig.configureStyleFilename("your-style.json")
MapMetricsLibraryConfig.configureBaseUrl("https://your-domain.com/styles/")
```

## Default Values

If not configured, the library uses these defaults:

- **Base URL**: `https://gateway.mapmetrics-atlas.net/styles/`
- **Style Filename**: `dd508822-9502-4ab5-bfe2-5e6ed5809c2d/portal.json`
- **Token**: `YOUR_TOKEN_HERE` (placeholder)

## Validation

Check if your configuration is valid:

```kotlin
if (MapMetricsLibraryConfig.hasValidConfiguration()) {
    // Configuration is valid, proceed with map initialization
    map.setStyle(Style.Builder().fromUri(TestStyles.MAPATLAS))
} else {
    // Show error or use fallback style
    map.setStyle(Style.Builder().fromUri(TestStyles.DEMOTILES))
}
```

## Debugging

Get the current style URL for debugging:

```kotlin
val currentUrl = MapMetricsLibraryConfig.getCurrentStyleUrl()
Log.d("MapMetrics", "Current style URL: $currentUrl")
```

## Testing

Clear configuration for testing:

```kotlin
MapMetricsLibraryConfig.clearConfiguration()
```

## Library Integration

The configuration is automatically used by:

1. **TestStyles.MAPATLAS** - Returns the configured style URL
2. **TestStyles.getMapMetricsStyle()** - Returns the configured style URL
3. **Global predefined styles** - Your style is included in the predefined styles list
4. **Cookie initialization** - Uses your token for authentication

## Security Notes

- Tokens are stored in SharedPreferences (encrypted on Android 6.0+)
- Consider additional encryption for sensitive tokens
- Tokens are not logged or exposed in debug output
- Configuration is app-specific and isolated

## Migration from Hardcoded Configuration

If you previously had hardcoded tokens in the library:

### Before (Hardcoded)
```kotlin
const val MAPATLAS = "https://gateway.mapmetrics-atlas.net/styles/?fileName=style.json&token=hardcoded-token"
```

### After (Configurable)
```kotlin
// In your Application.onCreate()
MapMetricsLibraryConfig.configureToken("your-token-here")

// In your activities
map.setStyle(Style.Builder().fromUri(TestStyles.MAPATLAS))
```

## Example Implementation

See `MapLibreApplication.kt` for a complete example of how to integrate this configuration system into your application. 