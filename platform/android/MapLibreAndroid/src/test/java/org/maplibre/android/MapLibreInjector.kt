package org.maplibre.android

import android.content.Context
import org.maplibre.android.util.TileServerOptions

object MapLibreInjector {
    private const val FIELD_INSTANCE = "INSTANCE"
    @JvmStatic
    fun inject(context: Context, apiKey: String,
               options: TileServerOptions) {
        val maplibre = MapMetrics(context, apiKey, options)
        try {
            val instance = MapMetrics::class.java.getDeclaredField(FIELD_INSTANCE)
            instance.isAccessible = true
            instance[maplibre] = maplibre
        } catch (exception: Exception) {
            throw AssertionError()
        }
    }

    @JvmStatic
    fun clear() {
        try {
            val field = MapMetrics::class.java.getDeclaredField(FIELD_INSTANCE)
            field.isAccessible = true
            field[field] = null
        } catch (exception: Exception) {
            throw AssertionError()
        }
    }
}
