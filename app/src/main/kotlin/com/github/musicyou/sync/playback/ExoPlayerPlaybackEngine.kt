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
import kotlinx.coroutines.cancel

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
                 Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                 Player.EVENT_MEDIA_ITEM_TRANSITION
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
    override fun loadTrack(mediaId: String, seekPositionMs: Long, autoPlay: Boolean, customUri: String?) {
        android.util.Log.d("MusicSyncFlow", "loadTrack: Resolving and Loading $mediaId, seekTo=$seekPositionMs, autoPlay=$autoPlay, customUri=$customUri")
        
        // Launch a coroutine to fetch metadata
        scope.launch {
            var validMetadataItem: MediaItem? = null
            
            // 1. Resolve Metadata (Always try to get rich metadata)
            // Check Database first
            val dbSong = Database.song(mediaId).firstOrNull()
            if (dbSong != null) {
                 android.util.Log.d("MusicSyncFlow", "ExoEngine: Found metadata in DB for $mediaId - ${dbSong.title}")
                 validMetadataItem = dbSong.asMediaItem
            } else {
                // Fallback to Innertube API
                try {
                    val result = Innertube.song(mediaId)
                    val songItem = result?.getOrNull()
                    if (songItem != null) {
                        android.util.Log.d("MusicSyncFlow", "ExoEngine: Found metadata in Innertube for $mediaId - ${songItem.info?.name}")
                        validMetadataItem = songItem.asMediaItem
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MusicSyncFlow", "ExoEngine: Innertube lookup failed: ${e.message}")
                }
            }

            // 2. Construct Final MediaItem
            val mediaItem = if (customUri != null) {
                android.util.Log.i("MusicSyncFlow", "ExoEngine: Using Custom URI override: $customUri")
                // If we have valid metadata, use it but override the URI
                if (validMetadataItem != null) {
                     validMetadataItem.buildUpon()
                         .setUri(customUri)
                         .setMediaId(mediaId)
                         .setCustomCacheKey(customUri)
                         .build()
                } else {
                    // No metadata found, just use basic item with URI
                    MediaItem.Builder()
                         .setUri(customUri)
                         .setMediaId(mediaId)
                         .setCustomCacheKey(customUri)
                         .build()
                }
            } else {
                // No custom URI, use the resolved metadata item (or fallback to basic if null)
                validMetadataItem ?: MediaItem.Builder()
                    .setUri(mediaId)
                    .setMediaId(mediaId)
                    .setCustomCacheKey(mediaId)
                    .build()
            }
            
            // Apply on main thread - seek and play AFTER track is set
            runOnMain {
                player.setMediaItem(mediaItem)
                player.prepare()
                if (seekPositionMs > 0) {
                    android.util.Log.d("MusicSyncFlow", "loadTrack: Seeking to $seekPositionMs ms")
                    player.seekTo(seekPositionMs)
                }
                if (autoPlay) {
                    android.util.Log.d("MusicSyncFlow", "loadTrack: Starting playback")
                    player.play()
                }
            }
        }
    }

    override fun loadMediaItem(mediaItem: MediaItem, seekPositionMs: Long, autoPlay: Boolean) {
        android.util.Log.d("MusicSyncFlow", "loadMediaItem: Loading ${mediaItem.mediaId}, seekTo=$seekPositionMs, autoPlay=$autoPlay")
        val safeItem = if (mediaItem.localConfiguration == null) {
            val uriString = mediaItem.mediaId
            if (uriString.startsWith("content://") || uriString.startsWith("file://") || uriString.startsWith("http://") || uriString.startsWith("https://")) {
                mediaItem.buildUpon()
                    .setUri(android.net.Uri.parse(uriString))
                    .setCustomCacheKey(uriString)
                    .build()
            } else {
                loadTrack(mediaItem.mediaId, seekPositionMs, autoPlay, null)
                return
            }
        } else {
            mediaItem
        }

        runOnMain {
            player.setMediaItem(safeItem)
            player.prepare()
            if (seekPositionMs > 0) {
                android.util.Log.d("MusicSyncFlow", "loadMediaItem: Seeking to $seekPositionMs ms")
                player.seekTo(seekPositionMs)
            }
            if (autoPlay) {
                android.util.Log.d("MusicSyncFlow", "loadMediaItem: Starting playback")
                player.play()
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

    override suspend fun getCurrentPosition(): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        player.currentPosition
    }

    override fun release() {
        runOnMain {
            player.removeListener(playerListener)
            player.release()
        }
        try {
            scope.cancel()
        } catch (e: Exception) {
            android.util.Log.e("MusicSyncFlow", "Error cancelling scope", e)
        }
    }

    private fun updateState() {
        // State updates usually come from listeners (Main Thread)
        // But to be safe if called internally
        runOnMain {
             _playbackState.update {
                PlaybackState(
                    mediaId = player.currentMediaItem?.mediaId,
                    mediaItem = player.currentMediaItem,
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
