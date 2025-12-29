package com.github.musicyou.sync.playback

/**
 * Snapshot of the current playback state.
 * Contains NO global timestamps.
 */
data class PlaybackState(
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = STATE_IDLE,
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
) {
    companion object {
        // Mapped from Player.STATE_*
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}
