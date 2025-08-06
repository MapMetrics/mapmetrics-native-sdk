package org.maplibre.android.location

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import org.maplibre.android.maps.MapMetricsMap

internal class MapLibreAnimatorListener(cancelableCallback: MapMetricsMap.CancelableCallback?) :
    AnimatorListenerAdapter() {
    private val cancelableCallback: MapMetricsMap.CancelableCallback?

    init {
        this.cancelableCallback = cancelableCallback
    }

    override fun onAnimationCancel(animation: Animator) {
        cancelableCallback?.onCancel()
    }

    override fun onAnimationEnd(animation: Animator) {
        cancelableCallback?.onFinish()
    }
}
