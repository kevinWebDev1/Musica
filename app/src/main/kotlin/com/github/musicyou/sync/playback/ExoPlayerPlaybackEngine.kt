package com.github.musicyou.sync.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.innertube.Innertube
import com.github.innertube.requests.song
import com.github.musicyou.Database
import com.github.musicyou.utils.asMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ExoPlayer-based implementation of PlaybackEngine.
 * Controlled STRICTLY by imperative commands.
 */
class ExoPlayerPlaybackEngine(
    private val context: Context,
    private val player: ExoPlayer // Injected or created internally
) : PlaybackEngine {

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            updateState()
        }
        
        override fun onEvents(player: Player, events: Player.Events) {
            // General catch-all for state updates
             if (events.containsAny(
                 Player.EVENT_PLAYBACK_STATE_CHANGED,
                 Player.EVENT_IS_PLAYING_CHANGED,
                 Player.EVENT_POSITION_DISCONTINUITY,
                 Player.EVENT_PLAYBACK_PARAMETERS_CHANGED
             )) {
                 updateState()
             }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    init {
        runOnMain {
            player.addListener(playerListener)
            updateState() // Initial state
        }
    }

    override fun prepare() {
        runOnMain { player.prepare() }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun loadTrack(mediaId: String, seekPositionMs: Long, autoPlay: Boolean) {
        android.util.Log.d("ExoPPlaybackEngine", "loadTrack: Resolving and Loading $mediaId, seekTo=$seekPositionMs, autoPlay=$autoPlay")
        
        // Launch a coroutine to fetch metadata
        scope.launch {
            var mediaItem: MediaItem? = null
            
            // Try database first (local cache)
            val dbSong = Database.song(mediaId).firstOrNull()
            if (dbSong != null) {
                android.util.Log.d("ExoPPlaybackEngine", "loadTrack: Using DB song - ${dbSong.title}")
                mediaItem = dbSong.asMediaItem
            } else {
                // Fallback to Innertube API
                try {
                    val result = Innertube.song(mediaId)
                    val songItem = result?.getOrNull()
                    if (songItem != null) {
                        android.util.Log.d("ExoPPlaybackEngine", "loadTrack: Using Innertube - ${songItem.info?.name}")
                        mediaItem = songItem.asMediaItem
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ExoPPlaybackEngine", "loadTrack: Innertube failed: ${e.message}")
                }
            }
            
            // Final fallback - basic MediaItem with just ID
            if (mediaItem == null) {
                android.util.Log.w("ExoPPlaybackEngine", "loadTrack: Using basic MediaItem (no metadata)")
                mediaItem = MediaItem.Builder()
                    .setUri(mediaId)
                    .setMediaId(mediaId)
                    .setCustomCacheKey(mediaId)
                    .build()
            }
            
            // Apply on main thread - seek and play AFTER track is set
            runOnMain {
                player.setMediaItem(mediaItem!!)
                player.prepare()
                if (seekPositionMs > 0) {
                    android.util.Log.d("ExoPPlaybackEngine", "loadTrack: Seeking to $seekPositionMs ms")
                    player.seekTo(seekPositionMs)
                }
                if (autoPlay) {
                    android.util.Log.d("ExoPPlaybackEngine", "loadTrack: Starting playback")
                    player.play()
                }
            }
        }
    }

    override fun play() {
        runOnMain { player.play() }
    }

    override fun pause() {
        runOnMain { player.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runOnMain { player.seekTo(positionMs) }
    }

    override fun setVolume(volume: Float) {
        runOnMain { player.volume = volume }
    }

    override fun setPlaybackSpeed(speed: Float) {
        runOnMain { player.setPlaybackSpeed(speed) }
    }

    override fun release() {
        runOnMain {
            player.removeListener(playerListener)
            player.release()
        }
    }

    private fun updateState() {
        // State updates usually come from listeners (Main Thread)
        // But to be safe if called internally
        runOnMain {
             _playbackState.update {
                PlaybackState(
                    mediaId = player.currentMediaItem?.mediaId,
                    isPlaying = player.isPlaying,
                    playbackState = mapExoState(player.playbackState),
                    currentPositionMs = player.currentPosition,
                    bufferedPositionMs = player.bufferedPosition,
                    playbackSpeed = player.playbackParameters.speed
                )
            }
        }
    }

    private fun mapExoState(exoState: Int): Int {
        return when (exoState) {
            Player.STATE_IDLE -> PlaybackState.STATE_IDLE
            Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            Player.STATE_READY -> PlaybackState.STATE_READY
            Player.STATE_ENDED -> PlaybackState.STATE_ENDED
            else -> PlaybackState.STATE_IDLE
        }
    }
}
