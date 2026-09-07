package com.github.musicyou.sync.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Multiplexes between ExoPlayerPlaybackEngine and YouTubePlaybackEngine.
 */
class HybridPlaybackEngine(
    val exoPlayerEngine: PlaybackEngine,
    val youtubeEngine: YouTubePlaybackEngine,
    coroutineScope: CoroutineScope
) : PlaybackEngine {

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var activeEngine: PlaybackEngine = exoPlayerEngine

    init {
        // Forward state from active engine
        coroutineScope.launch {
            var lastMediaId: String? = null
            var lastIsPlaying = false
            var lastPlaybackState = PlaybackState.STATE_IDLE
            
            exoPlayerEngine.playbackState.collect { state ->
                // Auto-switch to exoPlayer ONLY on explicit state transitions (prevents stealing focus due to stale async state)
                val incomingMediaId = state.mediaItem?.mediaId
                val isNewTrack = incomingMediaId != null && incomingMediaId != lastMediaId
                val startedPlaying = !lastIsPlaying && state.isPlaying
                val startedBuffering = lastPlaybackState != PlaybackState.STATE_BUFFERING && state.playbackState == PlaybackState.STATE_BUFFERING
                
                // We only want to block ExoPlayer from stealing focus if the incoming track is somehow a YouTube embed (which shouldn't happen for ExoPlayer).
                // We DO want ExoPlayer to steal focus if the user started playing a new local/remote track.
                val isIncomingYoutubeEmbed = incomingMediaId?.startsWith("youtube-embed:") == true
                
                if (!isIncomingYoutubeEmbed && activeEngine != exoPlayerEngine && (startedPlaying || startedBuffering || isNewTrack)) {
                    android.util.Log.d("HybridPlaybackEngine", "Auto-switching active engine to ExoPlayer (startedPlaying=$startedPlaying, startedBuffering=$startedBuffering, isNewTrack=$isNewTrack)")
                    activeEngine.pause()
                    activeEngine = exoPlayerEngine
                }
                
                lastMediaId = state.mediaItem?.mediaId
                lastIsPlaying = state.isPlaying
                lastPlaybackState = state.playbackState
                
                if (activeEngine == exoPlayerEngine) {
                    _playbackState.value = state
                }
            }
        }
        coroutineScope.launch {
            youtubeEngine.playbackState.collect { state ->
                if (activeEngine == youtubeEngine) {
                    _playbackState.value = state
                }
            }
        }
    }

    override fun prepare() {
        exoPlayerEngine.prepare()
        youtubeEngine.prepare()
    }

    override fun loadTrack(mediaId: String, seekPositionMs: Long, autoPlay: Boolean, customUri: String?) {
        val nextEngine = if (mediaId.startsWith("youtube-embed:")) {
            youtubeEngine
        } else {
            exoPlayerEngine
        }
        
        android.util.Log.d("ytSync", "HybridPlaybackEngine: loadTrack mediaId=$mediaId. Selected engine: ${nextEngine::class.simpleName}")
        android.util.Log.d("HybridPlaybackEngine", "loadTrack mediaId=$mediaId. Selected engine: ${nextEngine::class.simpleName}")

        if (activeEngine != nextEngine) {
            android.util.Log.d("ytSync", "HybridPlaybackEngine: Switching active engine from ${activeEngine::class.simpleName} to ${nextEngine::class.simpleName}")
            android.util.Log.d("HybridPlaybackEngine", "Switching active engine from ${activeEngine::class.simpleName} to ${nextEngine::class.simpleName}")
            activeEngine.pause()
            activeEngine = nextEngine
        }
        
        activeEngine.loadTrack(mediaId, seekPositionMs, autoPlay, customUri)
    }

    override fun loadMediaItem(mediaItem: androidx.media3.common.MediaItem, seekPositionMs: Long, autoPlay: Boolean) {
        val nextEngine = if (mediaItem.mediaId.startsWith("youtube-embed:")) {
            youtubeEngine
        } else {
            exoPlayerEngine
        }
        
        android.util.Log.d("ytSync", "HybridPlaybackEngine: loadMediaItem mediaId=${mediaItem.mediaId}. Selected engine: ${nextEngine::class.simpleName}")
        android.util.Log.d("HybridPlaybackEngine", "loadMediaItem mediaId=${mediaItem.mediaId}. Selected engine: ${nextEngine::class.simpleName}")

        if (activeEngine != nextEngine) {
            android.util.Log.d("ytSync", "HybridPlaybackEngine: Switching active engine from ${activeEngine::class.simpleName} to ${nextEngine::class.simpleName}")
            android.util.Log.d("HybridPlaybackEngine", "Switching active engine from ${activeEngine::class.simpleName} to ${nextEngine::class.simpleName}")
            activeEngine.pause()
            activeEngine = nextEngine
        }
        
        activeEngine.loadMediaItem(mediaItem, seekPositionMs, autoPlay)
    }

    override fun play() {
        android.util.Log.d("HybridPlaybackEngine", "play called. Routing to ${activeEngine::class.simpleName}")
        activeEngine.play()
    }

    override fun pause() {
        android.util.Log.d("HybridPlaybackEngine", "pause called. Routing to ${activeEngine::class.simpleName}")
        activeEngine.pause()
    }

    override fun seekTo(positionMs: Long) {
        android.util.Log.d("HybridPlaybackEngine", "seekTo $positionMs called. Routing to ${activeEngine::class.simpleName}")
        activeEngine.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) {
        activeEngine.setVolume(volume)
    }

    override fun setPlaybackSpeed(speed: Float) {
        activeEngine.setPlaybackSpeed(speed)
    }

    override suspend fun getCurrentPosition(): Long {
        return activeEngine.getCurrentPosition()
    }

    override fun release() {
        exoPlayerEngine.release()
        youtubeEngine.release()
    }
}
