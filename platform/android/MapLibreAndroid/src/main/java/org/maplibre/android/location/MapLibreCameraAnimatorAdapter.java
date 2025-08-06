package org.maplibre.android.location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;

import org.maplibre.android.maps.MapMetricsMap;

class MapLibreCameraAnimatorAdapter extends MapLibreFloatAnimator {

  MapLibreCameraAnimatorAdapter(@NonNull @Size(min = 2) Float[] values,
                                AnimationsValueChangeListener updateListener,
                                @Nullable MapMetricsMap.CancelableCallback cancelableCallback) {
    super(values, updateListener, Integer.MAX_VALUE);
    addListener(new MapLibreAnimatorListener(cancelableCallback));
  }
}
