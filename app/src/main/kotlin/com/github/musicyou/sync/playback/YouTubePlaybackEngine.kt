package com.github.musicyou.sync.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * PlaybackEngine implementation for YouTube IFrame API.
 * This engine doesn't play audio directly; instead, it holds the state
 * and broadcasts commands. The UI layer (which holds the WebView) observes
 * this engine and updates the WebView, and vice versa.
 */
class YouTubePlaybackEngine : PlaybackEngine {

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Used by UI to report its current position back to the engine
    @Volatile
    private var uiReportedPositionMs: Long = 0L

    override fun prepare() {
        // No-op for this engine, UI handles preparation
    }

    override fun loadTrack(mediaId: String, seekPositionMs: Long, autoPlay: Boolean, customUri: String?) {
        _playbackState.update {
            it.copy(
                mediaId = mediaId,
                currentPositionMs = seekPositionMs,
                isPlaying = autoPlay,
                playbackState = if (autoPlay) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_READY
            )
        }
        uiReportedPositionMs = seekPositionMs
    }

    override fun loadMediaItem(mediaItem: androidx.media3.common.MediaItem, seekPositionMs: Long, autoPlay: Boolean) {
        val mediaId = mediaItem.mediaId
        _playbackState.update {
            it.copy(
                mediaId = mediaId,
                mediaItem = mediaItem,
                currentPositionMs = seekPositionMs,
                isPlaying = autoPlay,
                playbackState = if (autoPlay) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_READY
            )
        }
        uiReportedPositionMs = seekPositionMs
    }

    override fun play() {
        android.util.Log.d("YouTubePlaybackEngine", "play called")
        _playbackState.update { it.copy(isPlaying = true) }
    }

    override fun pause() {
        android.util.Log.d("YouTubePlaybackEngine", "pause called")
        _playbackState.update { it.copy(isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        android.util.Log.d("YouTubePlaybackEngine", "seekTo called with positionMs=$positionMs")
        uiReportedPositionMs = positionMs
        _playbackState.update { 
            it.copy(
                currentPositionMs = positionMs,
                seekRequestId = it.seekRequestId + 1
            )
        }
    }

    override fun setVolume(volume: Float) {
        // Optional: Could emit a volume change event for the UI to apply
    }

    override fun setPlaybackSpeed(speed: Float) {
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    override suspend fun getCurrentPosition(): Long {
        return uiReportedPositionMs
    }

    override fun release() {
        _playbackState.value = PlaybackState()
    }

    // --- Methods for the UI to report state back to the Engine ---

    fun reportStateChange(isPlaying: Boolean, playbackState: Int) {
        android.util.Log.d("YouTubePlaybackEngine", "UI reported state change -> isPlaying=$isPlaying, playbackState=$playbackState (engine isPlaying kept as ${_playbackState.value.isPlaying})")
        // IMPORTANT: Do NOT overwrite isPlaying here. The isPlaying flag represents
        // the user's INTENT (play/pause button). If we let the YouTube player's
        // transient states (BUFFERING → isPlaying=false) feed back into the engine,
        // the command state machine will react by calling pause(), creating a
        // feedback loop that prevents videos from ever playing.
        _playbackState.update {
            it.copy(
                playbackState = playbackState
            )
        }
    }

    fun reportPosition(positionMs: Long) {
        if (positionMs - uiReportedPositionMs > 1000 || uiReportedPositionMs - positionMs > 1000) {
            android.util.Log.d("YouTubePlaybackEngine", "UI reported position jump -> $positionMs")
        }
        uiReportedPositionMs = positionMs
        _playbackState.update {
            it.copy(currentPositionMs = positionMs)
        }
    }

    fun reportDuration(durationMs: Long) {
        _playbackState.update {
            it.copy(durationMs = durationMs)
        }
    }
}
