# Scroll by Method

[//]: # ({{ activity_source_note&#40;"ScrollByActivity.kt"&#41; }})

This example shows how you can move the map by x/y pixels.

```kotlin
maplibreMap.scrollBy(
    (seekBarX.progress * MULTIPLIER_PER_PIXEL).toFloat(),
    (seekBarY.progress * MULTIPLIER_PER_PIXEL).toFloat()
)
```

[//]: # (<figure markdown="span">)

[//]: # (  ![Screenshot of Example Activity to move the map by some pixels]&#40;https://github.com/user-attachments/assets/f8ae0ec7-a165-4fb3-ab9f-bfb5579c7dd8&#41;{ width="300" })

[//]: # (</figure>)
