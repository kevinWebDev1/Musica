package com.github.musicyou.ui.screens.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale

/**
 * A thin Compose wrapper that attaches a pre-created [YouTubePlayerView] into
 * the current layout. The view is **never** created or destroyed here — it is
 * retained at the Activity level and simply moved between parent ViewGroups.
 *
 * All command logic (load / play / pause / seek) is handled by a centralised
 * LaunchedEffect in [com.github.musicyou.MainActivity].
 */
@Composable
fun YouTubePlayerSurface(
    retainedView: YouTubePlayerView,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        var scale = 1f
        if (width > 0 && height > 0) {
            val containerRatio = width / height
            val videoRatio = 16f / 9f
            
            // CenterCrop logic: scale up to eliminate black bars
            scale = if (containerRatio > videoRatio) {
                containerRatio / videoRatio
            } else {
                videoRatio / containerRatio
            }
        }

        AndroidView(
            modifier = Modifier
                .matchParentSize()
                .scale(scale),
        factory = { context ->
            android.util.Log.d("YouTubePlayerSurface", "factory: creating wrapper for YouTubePlayerView")
            val wrapper = android.widget.FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
            // Detach from any existing parent before attaching to the new wrapper.
            val currentParent = retainedView.parent as? ViewGroup
            if (currentParent != null) {
                android.util.Log.d("YouTubePlayerSurface", "factory: removing from old parent")
                currentParent.removeView(retainedView)
            }
            // Ensure the retained view is fully visible and hardware-accelerated
            retainedView.visibility = android.view.View.VISIBLE
            retainedView.alpha = 1f
            retainedView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            android.util.Log.d("YouTubePlayerSurface", "factory: adding to new wrapper, retainedView=${retainedView.width}x${retainedView.height}, visibility=${retainedView.visibility}")
            wrapper.addView(
                retainedView,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            wrapper
        },
        onRelease = { wrapper: android.widget.FrameLayout ->
            android.util.Log.d("YouTubePlayerSurface", "onRelease: wrapper disposing")
            // Only remove the retainedView if it's still a child of THIS wrapper.
            // If another wrapper has already stolen it (e.g., fullscreen player took
            // it from the background keep-alive box), removing it here would detach
            // it from its new parent and make the video invisible.
            if (retainedView.parent == wrapper) {
                android.util.Log.d("YouTubePlayerSurface", "onRelease: view still ours, removing")
                wrapper.removeView(retainedView)
            } else {
                android.util.Log.d("YouTubePlayerSurface", "onRelease: view already stolen by another parent, skipping")
            }
        }
    )
    }
}
