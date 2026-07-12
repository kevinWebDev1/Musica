package com.github.musicyou
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions

fun main() {
    for (m in YouTubePlayerView::class.java.methods) {
        if (m.name.contains("initialize")) {
            println("initialize: " + m.parameterTypes.joinToString { it.name })
        }
    }
}
