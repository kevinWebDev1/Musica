package com.github.musicyou.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.SQLException
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.support.v4.media.session.MediaSessionCompat
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.github.innertube.Innertube
import com.github.innertube.models.NavigationEndpoint
import com.github.innertube.requests.player
import com.github.musicyou.Database
import com.github.musicyou.MainActivity
import com.github.musicyou.R
import com.github.musicyou.enums.ExoPlayerDiskCacheMaxSize
import com.github.musicyou.models.Event
import com.github.musicyou.models.QueuedMediaItem
import com.github.musicyou.query
import com.github.musicyou.utils.InvincibleService
import com.github.musicyou.utils.RingBuffer
import com.github.musicyou.utils.TimerJob
import com.github.musicyou.utils.YouTubeRadio
import com.github.musicyou.utils.activityPendingIntent
import com.github.musicyou.utils.broadCastPendingIntent
import com.github.musicyou.utils.exoPlayerDiskCacheMaxSizeKey
import com.github.musicyou.utils.findNextMediaItemById
import com.github.musicyou.utils.forcePlayFromBeginning
import com.github.musicyou.utils.forceSeekToNext
import com.github.musicyou.utils.forceSeekToPrevious
import com.github.musicyou.utils.getEnum
import com.github.musicyou.utils.intent
import com.github.musicyou.utils.isAtLeastAndroid13
import com.github.musicyou.utils.isAtLeastAndroid6
import com.github.musicyou.utils.isAtLeastAndroid8
import com.github.musicyou.utils.isInvincibilityEnabledKey
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.utils.isShowingThumbnailInLockscreenKey
import com.github.musicyou.utils.mediaItems
import com.github.musicyou.utils.persistentQueueKey
import com.github.musicyou.utils.preferences
import kotlinx.coroutines.launch
import com.github.musicyou.utils.queueLoopEnabledKey
import com.github.musicyou.utils.resumePlaybackWhenDeviceConnectedKey
import com.github.musicyou.utils.shouldBePlaying
import com.github.musicyou.utils.skipSilenceKey
import com.github.musicyou.utils.timer
import com.github.musicyou.utils.trackLoopEnabledKey
import com.github.musicyou.utils.volumeNormalizationKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import android.os.Binder as AndroidBinder
import com.github.musicyou.sync.presence.PresenceManager

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerService : InvincibleService(), Player.Listener, PlaybackStatsListener.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var mediaSession: MediaSession
    private lateinit var cache: SimpleCache
    private lateinit var player: ExoPlayer

    private val stateBuilder
        get() = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY
                        or PlaybackState.ACTION_PAUSE
                        or PlaybackState.ACTION_PLAY_PAUSE
                        or PlaybackState.ACTION_STOP
                        or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackState.ACTION_SKIP_TO_NEXT
                        or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                        or PlaybackState.ACTION_SEEK_TO
                        or PlaybackState.ACTION_REWIND
            )
            .addCustomAction(
                FAVORITE_ACTION,
                "Toggle like",
                if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline
            )

    private val playbackStateMutex = Mutex()

    private val metadataBuilder = MediaMetadata.Builder()

    private var notificationManager: NotificationManager? = null

    private var timerJob: TimerJob? = null

    private var radio: YouTubeRadio? = null

    private lateinit var sessionManager: com.github.musicyou.sync.session.SessionManager
    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO) + Job()

    private var volumeNormalizationJob: Job? = null

    private var isPersistentQueueEnabled = false
    private var isShowingThumbnailInLockscreen = true
    private lateinit var transportManager: com.github.musicyou.sync.transport.TransportManager
    private lateinit var authManager: com.github.musicyou.auth.AuthManager
    override var isInvincibilityEnabled = false

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val binder = Binder()

    private var isNotificationStarted = false

    override val notificationId: Int
        get() = NOTIFICATION_ID

    private lateinit var notificationActionReceiver: NotificationActionReceiver

    private val mediaItemState = MutableStateFlow<MediaItem?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val isLikedState = mediaItemState
        .flatMapMerge { item ->
            item?.mediaId?.let {
                Database
                    .likedAt(it)
                    .distinctUntilChanged()
                    .cancellable()
            } ?: flowOf(null)
        }
        .map { it != null }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    override fun onBind(intent: Intent?): AndroidBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize PresenceManager with app context for privacy settings
        PresenceManager.initialize(applicationContext)

        bitmapProvider = BitmapProvider(
            context = applicationContext,
            bitmapSize = (256 * resources.displayMetrics.density).roundToInt(),
            colorProvider = { isSystemInDarkMode ->
                if (isSystemInDarkMode) Color.BLACK else Color.WHITE
            }
        )

        createNotificationChannel()

        preferences.registerOnSharedPreferenceChangeListener(this)

        val preferences = preferences
        isPersistentQueueEnabled = preferences.getBoolean(persistentQueueKey, false)
        isInvincibilityEnabled = preferences.getBoolean(isInvincibilityEnabledKey, false)
        isShowingThumbnailInLockscreen =
            preferences.getBoolean(isShowingThumbnailInLockscreenKey, false)

        val cacheEvictor = when (val size =
            preferences.getEnum(exoPlayerDiskCacheMaxSizeKey, ExoPlayerDiskCacheMaxSize.`2GB`)) {
            ExoPlayerDiskCacheMaxSize.Unlimited -> NoOpCacheEvictor()
            else -> LeastRecentlyUsedCacheEvictor(size.bytes)
        }

        // TODO: Remove in a future release
        val directory = cacheDir.resolve("exoplayer").also { directory ->
            if (directory.exists()) return@also

            directory.mkdir()

            cacheDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.length == 1 && file.name.isDigitsOnly() || file.extension == "uid") {
                    if (!file.renameTo(directory.resolve(file.name))) {
                        file.deleteRecursively()
                    }
                }
            }

            filesDir.resolve("coil").deleteRecursively()
        }
        cache = SimpleCache(directory, cacheEvictor, StandaloneDatabaseProvider(this))

        // Initialize Auth
        authManager = com.github.musicyou.auth.AuthManager(this)

        // Initialize Sync Components
        val timeSyncEngine = com.github.musicyou.sync.time.TimeSyncEngine()
        
        // Initialize Transports
        // Initialize Transports
        val nearbyTransport = com.github.musicyou.sync.transport.NearbyTransportLayer(this)
        
        // Disable WebRTC and WebSocket for now to prevent native crashes (SIGSEGV in libjingle)
        // val webRtcTransport = com.github.musicyou.sync.transport.WebRtcTransportLayer(this, object : com.github.musicyou.sync.transport.SignalingClient { ... })
        // val webSocketTransport = com.github.musicyou.sync.transport.WebSocketTransportLayer()
        
        transportManager = com.github.musicyou.sync.transport.TransportManager(
            listOf(nearbyTransport) // Only use Nearby for now
        )
        
        // Initialize ExoPlayer (Standard init)
        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setUsePlatformDiagnostics(false)
            .build()
            
        // Wrap ExoPlayer
        val playbackEngine = com.github.musicyou.sync.playback.ExoPlayerPlaybackEngine(this, player)
        
        // Initialize SessionManager
        // Initialize SessionManager
        sessionManager = com.github.musicyou.sync.session.SessionManager(
            timeSyncEngine,
            playbackEngine,
            transportManager, // Inject Transport
            coroutineScope
        )
        
        // Default to Host for testing if needed, or controlled by UI
        sessionManager.setEventBroadcaster { event ->
            // Serialize event and send via transport
            val bytes = com.github.musicyou.sync.protocol.SyncEventSerializer.toByteArray(event) 
            coroutineScope.launch { 
                transportManager.send(bytes) 
            }
        }
        
        // Listen for incoming messages from Transport
        coroutineScope.launch {
            transportManager.incomingMessages.collect { message ->
                try {
                    val event = com.github.musicyou.sync.protocol.SyncEventSerializer.fromByteArray(message.data)
                    if (event != null) {
                        android.util.Log.d("PlayerService", "Received Event from ${message.senderId}: $event")
                        sessionManager.processEvent(event, message.senderId)
                    } else {
                        android.util.Log.w("PlayerService", "Received null event or failed to parse (${message.data.size}b) from ${message.senderId}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlayerService", "Error processing incoming message", e)
                }
            }
        }
        
        // Listen for Session State to show 'Connected' toast
        coroutineScope.launch {
            sessionManager.sessionState.collect { state ->
                 if (state.connectedPeers.isNotEmpty()) {
                     // Check if this is a fresh connection event? 
                     // For now, simpler to just log or rely on the UI update.
                 }
            }
        }
        
        // Setup metadata provider to include track info in sync events
        // IMPORTANT: Player must only be accessed on main thread, so we cache metadata
        var cachedTitle: String? = null
        var cachedArtist: String? = null
        var cachedThumbnailUrl: String? = null
        
        // Update cached metadata whenever player state changes (on main thread)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                cachedTitle = mediaItem?.mediaMetadata?.title?.toString()
                cachedArtist = mediaItem?.mediaMetadata?.artist?.toString()
                cachedThumbnailUrl = mediaItem?.mediaMetadata?.artworkUri?.toString()
                android.util.Log.d("MusicSync", "Cached metadata updated: title=$cachedTitle, artist=$cachedArtist")
                
                // Update RTDB presence with current song
                if (mediaItem != null && cachedTitle != null && cachedArtist != null) {
                    PresenceManager.updateCurrentSong(
                        songId = mediaItem.mediaId,
                        title = cachedTitle,
                        artist = cachedArtist,
                        albumArt = cachedThumbnailUrl
                    )
                    android.util.Log.d("PresenceIntegration", "Updated current song: $cachedTitle - $cachedArtist")
                } else {
                    PresenceManager.clearCurrentSong()
                    android.util.Log.d("PresenceIntegration", "Cleared current song")
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    // Only clear if actually paused/stopped, NOT buffering
                    if (player.playbackState != androidx.media3.common.Player.STATE_BUFFERING) {
                         // Clear current song when playback stops
                        PresenceManager.clearCurrentSong()
                        android.util.Log.d("PresenceIntegration", "Playback stopped, cleared current song")
                    }
                } else {
                    // Resume/Start playing - restore presence
                    if (cachedTitle != null && cachedArtist != null) {
                         PresenceManager.updateCurrentSong(
                            songId = player.currentMediaItem?.mediaId ?: "unknown",
                            title = cachedTitle,
                            artist = cachedArtist,
                            albumArt = cachedThumbnailUrl
                        )
                        android.util.Log.d("PresenceIntegration", "Playback resumed, restored current song: $cachedTitle")
                    }
                }
            }
        })
        
        // Initialize with current media item
        player.currentMediaItem?.let { mediaItem ->
            cachedTitle = mediaItem.mediaMetadata.title?.toString()
            cachedArtist = mediaItem.mediaMetadata.artist?.toString()
            cachedThumbnailUrl = mediaItem.mediaMetadata.artworkUri?.toString()
        }
        
        // Provider returns cached values (safe to call from any thread)
        sessionManager.setMetadataProvider {
            Triple(cachedTitle, cachedArtist, cachedThumbnailUrl)
        }
        
        // Setup name provider to include user name in sync events
        sessionManager.setNameProvider {
            val username = preferences.getString(com.github.musicyou.utils.usernameKey, null)
            if (username != null) return@setNameProvider "@$username"
            preferences.getString(com.github.musicyou.utils.displayNameKey, null)
                ?: authManager.currentUser.value?.displayName 
                ?: com.github.musicyou.sync.SyncPreferences.getUserName(this@PlayerService)
        }
        
        // Setup avatar provider to include profile picture in sync events
        sessionManager.setAvatarProvider {
            preferences.getString(com.github.musicyou.utils.profileImageUrlKey, null)
                ?: authManager.currentUser.value?.photoUrl?.toString()
        }
        
        // Setup toast handler to show notifications when participants request changes
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var currentToast: android.widget.Toast? = null
        
        sessionManager.setToastHandler { message ->
            mainHandler.post {
                currentToast?.cancel()
                currentToast = android.widget.Toast.makeText(this@PlayerService, message, android.widget.Toast.LENGTH_SHORT)
                currentToast?.show()
            }
        }

        // Session starting is now controlled by SyncDialog with permission checks
        
        // Continue with remaining player setup
        setupPlayerAfterSessionManager()
        
        // Set presence to online when service starts
        coroutineScope.launch {
            ProfileManager.updatePresence("idle", null)
        }
    }

    private fun reinitializeSessionManager(newTransport: com.github.musicyou.sync.transport.TransportLayer) {
        android.util.Log.d("MusicSync", "Updating existing SessionManager with new transport")
        
        // Stop any active processes first
        sessionManager.stopSession()

        // Update the existing TransportManager instead of replacing it
        // This keeps all UI observers (StateFlows) alive and connected!
        transportManager.resetState()
        transportManager.updateTransports(listOf(newTransport))
        
        android.util.Log.d("MusicSync", "TransportManager updated successfully")
    }
    
    private fun setupPlayerAfterSessionManager() {
        player.repeatMode = when {
            preferences.getBoolean(trackLoopEnabledKey, false) -> Player.REPEAT_MODE_ONE
            preferences.getBoolean(queueLoopEnabledKey, false) -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }

        player.skipSilenceEnabled = preferences.getBoolean(skipSilenceKey, false)
        player.addListener(this)
        player.addAnalyticsListener(PlaybackStatsListener(false, this))

        maybeRestorePlayerQueue()

        mediaSession = MediaSession(baseContext, "PlayerService")
        mediaSession.setCallback(SessionCallback(player))
        mediaSession.setPlaybackState(stateBuilder.build())
        mediaSession.isActive = true

        coroutineScope.launch {
            isLikedState
                .onEach { withContext(Dispatchers.Main) { updatePlaybackState() } }
                .collect()
        }

        notificationActionReceiver = NotificationActionReceiver(player)

        val filter = IntentFilter().apply {
            addAction(Action.play.value)
            addAction(Action.pause.value)
            addAction(Action.next.value)
            addAction(Action.previous.value)
        }

        ContextCompat.registerReceiver(
            this,
            notificationActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        maybeResumePlaybackWhenDeviceConnected()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.shouldBePlaying) {
            broadCastPendingIntent<NotificationDismissReceiver>().send()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        maybeSavePlayerQueue()

        preferences.unregisterOnSharedPreferenceChangeListener(this)

        player.removeListener(this)
        player.stop()
        player.release()

        player.release()
        
        // Clean up Sync components
        if (::transportManager.isInitialized) {
            coroutineScope.launch {
                transportManager.disconnect()
            }
        }
        coroutineScope.cancel()

        unregisterReceiver(notificationActionReceiver)

        mediaSession.isActive = false
        mediaSession.release()
        cache.release()

        loudnessEnhancer?.release()
        
        // Set presence to offline when service stops
        coroutineScope.launch {
            ProfileManager.updatePresence("offline", null)
        }

        super.onDestroy()
    }

    override fun shouldBeInvincible(): Boolean {
        return !player.shouldBePlaying
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (bitmapProvider.setDefaultBitmap() && player.currentMediaItem != null) {
            notificationManager?.notify(NOTIFICATION_ID, notification())
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val mediaItem =
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        val totalPlayTimeMs = playbackStats.totalPlayTimeMs

        if (totalPlayTimeMs > 5000) {
            query {
                Database.incrementTotalPlayTimeMs(mediaItem.mediaId, totalPlayTimeMs)
            }
        }

        if (totalPlayTimeMs > 30000) {
            query {
                try {
                    Database.insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = System.currentTimeMillis(),
                            playTime = totalPlayTimeMs
                        )
                    )
                } catch (_: SQLException) {
                }
                coroutineScope.launch {
                    com.github.musicyou.auth.SyncManager.backupHistory()
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItemState.update { mediaItem }

        if (::sessionManager.isInitialized && mediaItem != null) {
            sessionManager.onTrackChanged(mediaItem.mediaId, reason)
        }

        maybeRecoverPlaybackError()
        maybeNormalizeVolume()
        maybeProcessRadio()

        if (mediaItem == null) {
            bitmapProvider.listener?.invoke(null)
        } else if (mediaItem.mediaMetadata.artworkUri == bitmapProvider.lastUri) {
            bitmapProvider.listener?.invoke(bitmapProvider.lastBitmap)
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            updateMediaSessionQueue(player.currentTimeline)
        }
    }

    // Sync play/pause state changes from any source
    private var lastIsPlaying = false
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (::sessionManager.isInitialized && sessionManager.sessionState.value.isHost) {
            // Only sync if state actually changed
            if (isPlaying != lastIsPlaying) {
                android.util.Log.d("MusicSync", "onIsPlayingChanged: $isPlaying -> broadcasting")
                if (isPlaying) {
                    sessionManager.resume()
                } else {
                    sessionManager.pause()
                }
            }
            lastIsPlaying = isPlaying
        }
    }

    // NOTE: onPositionDiscontinuity removed - it was causing infinite seek loops.
    // Seeks are synced via SessionCallback.onSeekTo() instead.
    // If UI slider bypasses SessionCallback, we'll need a different approach.

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            updateMediaSessionQueue(timeline)
        }
    }

    private fun updateMediaSessionQueue(timeline: Timeline) {
        val builder = MediaDescription.Builder()

        val currentMediaItemIndex = player.currentMediaItemIndex
        val lastIndex = timeline.windowCount - 1
        var startIndex = currentMediaItemIndex - 7
        var endIndex = currentMediaItemIndex + 7

        if (startIndex < 0) endIndex -= startIndex

        if (endIndex > lastIndex) {
            startIndex -= (endIndex - lastIndex)
            endIndex = lastIndex
        }

        startIndex = startIndex.coerceAtLeast(0)

        mediaSession.setQueue(
            List(endIndex - startIndex + 1) { index ->
                val mediaItem = timeline.getWindow(index + startIndex, Timeline.Window()).mediaItem
                MediaSession.QueueItem(
                    builder
                        .setMediaId(mediaItem.mediaId)
                        .setTitle(mediaItem.mediaMetadata.title)
                        .setSubtitle(mediaItem.mediaMetadata.artist)
                        .setIconUri(mediaItem.mediaMetadata.artworkUri)
                        .build(),
                    (index + startIndex).toLong()
                )
            }
        )
    }

    private fun maybeRecoverPlaybackError() {
        if (player.playerError != null) player.prepare()
    }

    private fun maybeProcessRadio() {
        radio?.let { radio ->
            if (player.mediaItemCount - player.currentMediaItemIndex <= 3) {
                coroutineScope.launch(Dispatchers.Main) {
                    player.addMediaItems(radio.process())
                }
            }
        }
    }

    private fun maybeSavePlayerQueue() {
        if (!isPersistentQueueEnabled) return

        val mediaItems = player.currentTimeline.mediaItems
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItemPosition = player.currentPosition

        mediaItems.mapIndexed { index, mediaItem ->
            QueuedMediaItem(
                mediaItem = mediaItem,
                position = if (index == mediaItemIndex) mediaItemPosition else null
            )
        }.let { queuedMediaItems ->
            query {
                Database.clearQueue()
                Database.insert(queuedMediaItems)
            }
        }
    }

    private fun maybeRestorePlayerQueue() {
        if (!isPersistentQueueEnabled) return

        query {
            val queuedSong = Database.queue()
            Database.clearQueue()

            if (queuedSong.isEmpty()) return@query

            val index = queuedSong.indexOfFirst { it.position != null }.coerceAtLeast(0)

            runBlocking(Dispatchers.Main) {
                player.setMediaItems(
                    queuedSong.map { mediaItem ->
                        mediaItem.mediaItem.buildUpon()
                            .setUri(mediaItem.mediaItem.mediaId)
                            .setCustomCacheKey(mediaItem.mediaItem.mediaId)
                            .build().apply {
                                mediaMetadata.extras?.putBoolean("isFromPersistentQueue", true)
                            }
                    },
                    index,
                    queuedSong[index].position ?: C.TIME_UNSET
                )
                player.prepare()

                isNotificationStarted = true
                startForegroundService(this@PlayerService, intent<PlayerService>())
                startForeground(NOTIFICATION_ID, notification())
            }
        }
    }

    private fun maybeNormalizeVolume() {
        if (!preferences.getBoolean(volumeNormalizationKey, false)) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            player.volume = 1f
            return
        }

        if (loudnessEnhancer == null) {
            loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }

        player.currentMediaItem?.mediaId?.let { songId ->
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob = coroutineScope.launch(Dispatchers.Main) {
                Database.loudnessDb(songId).cancellable().collectLatest { loudnessDb ->
                    try {
                        loudnessEnhancer?.setTargetGain(-((loudnessDb ?: 0f) * 100).toInt() + 500)
                        loudnessEnhancer?.enabled = true
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun maybeShowSongCoverInLockScreen() {
        val bitmap =
            if (isAtLeastAndroid13 || isShowingThumbnailInLockscreen) bitmapProvider.bitmap else null

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)

        if (isAtLeastAndroid13 && player.currentMediaItemIndex == 0) {
            metadataBuilder.putText(
                MediaMetadata.METADATA_KEY_TITLE,
                "${player.mediaMetadata.title} "
            )
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun maybeResumePlaybackWhenDeviceConnected() {
        if (!isAtLeastAndroid6) return

        if (preferences.getBoolean(resumePlaybackWhenDeviceConnectedKey, false)) {
            if (audioManager == null) {
                audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
            }

            audioDeviceCallback = object : AudioDeviceCallback() {
                private fun canPlayMusic(audioDeviceInfo: AudioDeviceInfo): Boolean {
                    if (!audioDeviceInfo.isSink) return false

                    return audioDeviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            (isAtLeastAndroid8 && audioDeviceInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET)
                }

                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    if (!player.isPlaying && addedDevices.any(::canPlayMusic)) {
                        player.play()
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = Unit
            }

            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)

        } else {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
        }
    }

    private fun sendOpenEqualizerIntent() {
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun sendCloseEqualizerIntent() {
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            }
        )
    }

    private fun updatePlaybackState() = coroutineScope.launch {
        playbackStateMutex.withLock {
            withContext(Dispatchers.Main) {
                mediaSession.setPlaybackState(
                    stateBuilder
                        .setState(player.androidPlaybackState, player.currentPosition, 1f)
                        .setBufferedPosition(player.bufferedPosition)
                        .build()
                )
            }
        }
    }

    private val Player.androidPlaybackState: Int
        get() = when (playbackState) {
            Player.STATE_BUFFERING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_NONE
        }

    @Suppress("DEPRECATION")
    override fun onEvents(player: Player, events: Player.Events) {
        if (player.duration != C.TIME_UNSET) {
            mediaSession.setMetadata(
                metadataBuilder
                    .putText(MediaMetadata.METADATA_KEY_TITLE, player.mediaMetadata.title)
                    .putText(MediaMetadata.METADATA_KEY_ARTIST, player.mediaMetadata.artist)
                    .putText(MediaMetadata.METADATA_KEY_ALBUM, player.mediaMetadata.albumTitle)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                    .build()
            )
        }

        updatePlaybackState()

        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY
            )
        ) {
            val notification = notification()

            if (notification == null) {
                isNotificationStarted = false
                makeInvincible(false)
                stopForeground(false)
                sendCloseEqualizerIntent()
                notificationManager?.cancel(NOTIFICATION_ID)
                return
            }

            if (player.shouldBePlaying && !isNotificationStarted) {
                isNotificationStarted = true
                startForegroundService(this@PlayerService, intent<PlayerService>())
                startForeground(NOTIFICATION_ID, notification)
                makeInvincible(false)
                sendOpenEqualizerIntent()
            } else {
                if (!player.shouldBePlaying) {
                    isNotificationStarted = false
                    stopForeground(false)
                    makeInvincible(true)
                    sendCloseEqualizerIntent()
                }
                notificationManager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            persistentQueueKey -> if (sharedPreferences != null) {
                isPersistentQueueEnabled =
                    sharedPreferences.getBoolean(key, isPersistentQueueEnabled)
            }

            volumeNormalizationKey -> maybeNormalizeVolume()

            resumePlaybackWhenDeviceConnectedKey -> maybeResumePlaybackWhenDeviceConnected()

            isInvincibilityEnabledKey -> if (sharedPreferences != null) {
                isInvincibilityEnabled =
                    sharedPreferences.getBoolean(key, isInvincibilityEnabled)
            }

            skipSilenceKey -> if (sharedPreferences != null) {
                player.skipSilenceEnabled = sharedPreferences.getBoolean(key, false)
            }

            isShowingThumbnailInLockscreenKey -> {
                if (sharedPreferences != null) {
                    isShowingThumbnailInLockscreen = sharedPreferences.getBoolean(key, true)
                }
                maybeShowSongCoverInLockScreen()
            }

            trackLoopEnabledKey, queueLoopEnabledKey -> {
                player.repeatMode = when {
                    preferences.getBoolean(trackLoopEnabledKey, false) -> Player.REPEAT_MODE_ONE
                    preferences.getBoolean(queueLoopEnabledKey, false) -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
    }

    override fun notification(): Notification? {
        if (player.currentMediaItem == null) return null

        val playIntent = Action.play.pendingIntent
        val pauseIntent = Action.pause.pendingIntent
        val nextIntent = Action.next.pendingIntent
        val prevIntent = Action.previous.pendingIntent

        val mediaMetadata = player.mediaMetadata

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(mediaMetadata.title)
            .setContentText(mediaMetadata.artist)
            .setSubText(player.playerError?.message)
            .setLargeIcon(bitmapProvider.bitmap)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSmallIcon(player.playerError?.let { R.drawable.alert_circle }
                ?: R.drawable.app_icon)
            .setOngoing(false)
            .setContentIntent(
                activityPendingIntent<MainActivity>(
                    flags = PendingIntent.FLAG_UPDATE_CURRENT
                ) { putExtra("expandPlayerBottomSheet", true) })
            .setDeleteIntent(broadCastPendingIntent<NotificationDismissReceiver>())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(MediaSessionCompat.Token.fromToken(mediaSession.sessionToken))
            )
            .addAction(R.drawable.play_skip_back, "Skip back", prevIntent)
            .addAction(
                if (player.shouldBePlaying) R.drawable.pause else R.drawable.play,
                if (player.shouldBePlaying) "Pause" else "Play",
                if (player.shouldBePlaying) pauseIntent else playIntent
            )
            .addAction(R.drawable.play_skip_forward, "Skip forward", nextIntent)

        bitmapProvider.load(mediaMetadata.artworkUri) { bitmap ->
            maybeShowSongCoverInLockScreen()
            notificationManager?.notify(NOTIFICATION_ID, builder.setLargeIcon(bitmap).build())
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService()

        if (!isAtLeastAndroid8) return

        notificationManager?.run {
            if (getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.now_playing),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    }
                )
            }

            if (getNotificationChannel(SLEEP_TIMER_NOTIFICATION_CHANNEL_ID) == null) {
                createNotificationChannel(
                    NotificationChannel(
                        SLEEP_TIMER_NOTIFICATION_CHANNEL_ID,
                        getString(R.string.sleep_timer),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    private fun createCacheDataSource(): DataSource.Factory {
        return CacheDataSource.Factory().setCache(cache).apply {
            setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(16000)
                    .setReadTimeoutMs(8000)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
            )
        }
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val chunkLength = 512 * 1024L
        val ringBuffer = RingBuffer<Pair<String, Uri>?>(2) { null }

        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val videoId = dataSpec.key ?: error("A key must be set")

            if (cache.isCached(videoId, dataSpec.position, chunkLength)) {
                dataSpec
            } else {
                when (videoId) {
                    ringBuffer.getOrNull(0)?.first -> dataSpec.withUri(ringBuffer.getOrNull(0)!!.second)
                    ringBuffer.getOrNull(1)?.first -> dataSpec.withUri(ringBuffer.getOrNull(1)!!.second)
                    else -> {
                        val urlResult = runBlocking(Dispatchers.IO) {
                            Innertube.player(videoId = videoId)
                        }?.mapCatching { body ->
                            if (body.videoDetails?.videoId != videoId) {
                                throw VideoIdMismatchException()
                            }

                            when (val status = body.playabilityStatus?.status) {
                                "OK" -> body.streamingData?.highestQualityFormat?.let { format ->
                                    val mediaItem = runBlocking(Dispatchers.Main) {
                                        player.findNextMediaItemById(videoId)
                                    }

                                    if (mediaItem?.mediaMetadata?.extras?.getString("durationText") == null) {
                                        format.approxDurationMs?.div(1000)
                                            ?.let(DateUtils::formatElapsedTime)?.removePrefix("0")
                                            ?.let { durationText ->
                                                mediaItem?.mediaMetadata?.extras?.putString(
                                                    "durationText",
                                                    durationText
                                                )
                                                Database.updateDurationText(videoId, durationText)
                                            }
                                    }

                                    query {
                                        mediaItem?.let(Database::insert)

                                        Database.insert(
                                            com.github.musicyou.models.Format(
                                                songId = videoId,
                                                itag = format.itag,
                                                mimeType = format.mimeType,
                                                bitrate = format.bitrate,
                                                loudnessDb = body.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                                contentLength = format.contentLength,
                                                lastModified = format.lastModified
                                            )
                                        )
                                    }

                                    format.url
                                } ?: throw PlayableFormatNotFoundException()

                                "UNPLAYABLE" -> throw UnplayableException()
                                "LOGIN_REQUIRED" -> throw LoginRequiredException()
                                else -> throw PlaybackException(
                                    status,
                                    null,
                                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                                )
                            }
                        }

                        urlResult?.getOrThrow()?.let { url ->
                            ringBuffer.append(videoId to url.toUri())
                            dataSpec.withUri(url.toUri())
                                .subrange(dataSpec.uriPositionOffset, chunkLength)
                        } ?: throw PlaybackException(
                            null,
                            urlResult?.exceptionOrNull(),
                            PlaybackException.ERROR_CODE_REMOTE_ERROR
                        )
                    }
                }
            }
        }
    }

    private fun createMediaSourceFactory(): MediaSource.Factory {
        return DefaultMediaSourceFactory(createDataSourceFactory(), DefaultExtractorsFactory())
    }

    private fun createRendersFactory(): RenderersFactory {
        val audioSink = DefaultAudioSink.Builder(applicationContext)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .setAudioOffloadSupportProvider(DefaultAudioOffloadSupportProvider(applicationContext))
            .setAudioProcessorChain(
                DefaultAudioProcessorChain(
                    emptyArray(),
                    SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                    SonicAudioProcessor()
                )
            )
            .build()

        return RenderersFactory { handler: Handler?, _, audioListener: AudioRendererEventListener?, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    this,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener,
                    audioSink
                )
            )
        }
    }

    inner class Binder : AndroidBinder() {
        val player: ExoPlayer
            get() = this@PlayerService.player

        val cache: Cache
            get() = this@PlayerService.cache

        val mediaSession
            get() = this@PlayerService.mediaSession

        val sleepTimerMillisLeft: StateFlow<Long?>?
            get() = timerJob?.millisLeft

        private var radioJob: Job? = null

        var isLoadingRadio by mutableStateOf(false)
            private set

        fun startSleepTimer(delayMillis: Long) {
            timerJob?.cancel()

            timerJob = coroutineScope.timer(delayMillis) {
                val notification = NotificationCompat
                    .Builder(this@PlayerService, SLEEP_TIMER_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.sleep_timer_ended))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .setSmallIcon(R.drawable.app_icon)
                    .build()

                notificationManager?.notify(SLEEP_TIMER_NOTIFICATION_ID, notification)

                stopSelf()
                exitProcess(0)
            }
        }

        fun cancelSleepTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        fun setupRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = true)

        fun playRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = false)

        private fun startRadio(endpoint: NavigationEndpoint.Endpoint.Watch?, justAdd: Boolean) {
            radioJob?.cancel()
            radio = null
            YouTubeRadio(
                endpoint?.videoId,
                endpoint?.playlistId,
                endpoint?.playlistSetVideoId,
                endpoint?.params
            ).let {
                isLoadingRadio = true
                radioJob = coroutineScope.launch(Dispatchers.Main) {
                    if (justAdd) {
                        player.addMediaItems(it.process().drop(1))
                    } else {
                        player.forcePlayFromBeginning(it.process())
                    }
                    radio = it
                    isLoadingRadio = false
                }
            }
        }

        fun stopRadio() {
            isLoadingRadio = false
            radioJob?.cancel()
            radio = null
        }

        val sessionManager: com.github.musicyou.sync.session.SessionManager
            get() = this@PlayerService.sessionManager
        
        /**
         * Check if a sync session is currently active.
         */
        val isSyncActive: Boolean
            get() = this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null
        
        /**
         * Sync-aware play. Routes through SessionManager when sync is active.
         * UI should use this instead of player.play() directly.
         */
        fun syncPlay() {
            if (this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
                android.util.Log.d("MusicSync", "syncPlay: Routing through SessionManager")
                sessionManager.resume()
            } else {
                android.util.Log.d("MusicSync", "syncPlay: No sync active, direct play")
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                else if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
                player.play()
            }
        }
        
        /**
         * Sync-aware pause. Routes through SessionManager when sync is active.
         * UI should use this instead of player.pause() directly.
         */
        fun syncPause() {
            if (this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
                android.util.Log.d("MusicSync", "syncPause: Routing through SessionManager")
                sessionManager.pause()
            } else {
                android.util.Log.d("MusicSync", "syncPause: No sync active, direct pause")
                player.pause()
            }
        }
        
        /**
         * Sync-aware seekTo. Routes through SessionManager when sync is active.
         * UI should use this instead of player.seekTo() directly.
         */
        fun syncSeekTo(positionMs: Long) {
            if (this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
                android.util.Log.d("MusicSync", "syncSeekTo: Routing through SessionManager, pos=$positionMs")
                sessionManager.seekTo(positionMs)
            } else {
                android.util.Log.d("MusicSync", "syncSeekTo: No sync active, direct seek")
                player.seekTo(positionMs)
            }
        }
        
        /**
         * Sync-aware skip to next track.
         * Blocks for participants only when Host-Only Mode is ON.
         */
        fun syncSkipNext() {
            if (this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
                val state = sessionManager.sessionState.value
                // Only block if Host-Only Mode is ON
                if (!state.isHost && state.hostOnlyMode) {
                    android.util.Log.d("MusicSync", "syncSkipNext: Participant blocked (Host-Only Mode)")
                    return
                }
                android.util.Log.d("MusicSync", "syncSkipNext: Skipping to next (sync active)")
                player.forceSeekToNext()
            } else {
                android.util.Log.d("MusicSync", "syncSkipNext: No sync active, direct skip")
                player.forceSeekToNext()
            }
        }
        
        /**
         * Sync-aware skip to previous track.
         * Blocks for participants only when Host-Only Mode is ON.
         */
        fun syncSkipPrevious() {
            if (this@PlayerService::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
                val state = sessionManager.sessionState.value
                // Only block if Host-Only Mode is ON
                if (!state.isHost && state.hostOnlyMode) {
                    android.util.Log.d("MusicSync", "syncSkipPrevious: Participant blocked (Host-Only Mode)")
                    return
                }
                android.util.Log.d("MusicSync", "syncSkipPrevious: Skipping to previous (sync active)")
                player.forceSeekToPrevious()
            } else {
                android.util.Log.d("MusicSync", "syncSkipPrevious: No sync active, direct skip")
                player.forceSeekToPrevious()
            }
        }
        
        /**
         * Start a sync session with the specified transport mode.
         * @param isLongDistance If true, use WebRTC for internet sync. If false, use Nearby for local sync.
         */
        fun startSyncSession(isLongDistance: Boolean) {
            android.util.Log.i("MusicSync", "startSyncSession: isLongDistance=$isLongDistance")
            
            coroutineScope.launch {
                // Stop any existing session first
                sessionManager.stopSession()
                
                // Create appropriate transport
                val newTransport: com.github.musicyou.sync.transport.TransportLayer = if (isLongDistance) {
                    android.util.Log.d("MusicSync", "Creating WebRTC transport for long distance")
                    com.github.musicyou.sync.transport.WebRtcTransportLayer(
                        this@PlayerService,
                        isHost = true
                    )
                } else {
                    android.util.Log.d("MusicSync", "Creating Nearby transport for local sync")
                    com.github.musicyou.sync.transport.NearbyTransportLayer(
                        this@PlayerService
                    )
                }
                
                // Reinitialize SessionManager with new transport
                reinitializeSessionManager(newTransport)
                
                // Start session
                sessionManager.startSession()
            }
        }
        
        /**
         * Join a sync session with the specified transport mode.
         * @param code The session code to join.
         * @param isLongDistance If true, use WebRTC for internet sync. If false, use Nearby for local sync.
         */
        fun joinSyncSession(code: String, isLongDistance: Boolean) {
            android.util.Log.i("MusicSync", "joinSyncSession: code=$code, isLongDistance=$isLongDistance")
            
            coroutineScope.launch {
                // Stop any existing session first
                sessionManager.stopSession()
                
                // Create appropriate transport
                val newTransport: com.github.musicyou.sync.transport.TransportLayer = if (isLongDistance) {
                    android.util.Log.d("MusicSync", "Creating WebRTC transport for long distance join")
                    com.github.musicyou.sync.transport.WebRtcTransportLayer(
                        this@PlayerService,
                        isHost = false
                    )
                } else {
                    android.util.Log.d("MusicSync", "Creating Nearby transport for local sync join")
                    com.github.musicyou.sync.transport.NearbyTransportLayer(
                        this@PlayerService
                    )
                }
                
                // Reinitialize SessionManager with new transport
                reinitializeSessionManager(newTransport)
                
                // Join session
                sessionManager.joinSession(code)
            }
        }
    }

    private fun likeAction() = mediaItemState.value?.let { mediaItem ->
        query {
            runCatching {
                Database.like(
                    songId = mediaItem.mediaId,
                    likedAt = if (isLikedState.value) null else System.currentTimeMillis()
                )
            }
        }
    }

    private fun play() {
        if (::sessionManager.isInitialized && sessionManager.sessionState.value.sessionId != null) {
            android.util.Log.d("MusicSync", "play: Routing through SessionManager")
            sessionManager.resume()
        } else {
            android.util.Log.d("MusicSync", "play: No sync active, direct play")
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            else if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
            player.play()
        }
    }


    private inner class SessionCallback(private val player: Player) : MediaSession.Callback() {
        override fun onPlay() = play()
        override fun onPause() {
            if (::sessionManager.isInitialized) sessionManager.pause()
            else player.pause()
        }
        override fun onSkipToPrevious() {
            // Block for participants in Host-Only Mode
            if (::sessionManager.isInitialized) {
                val state = sessionManager.sessionState.value
                if (state.sessionId != null && !state.isHost && state.hostOnlyMode) {
                    android.util.Log.d("MusicSync", "SessionCallback: Blocking skipPrevious (Host-Only Mode)")
                    return
                }
            }
            runCatching(player::forceSeekToPrevious)
        }
        override fun onSkipToNext() {
            // Block for participants in Host-Only Mode
            if (::sessionManager.isInitialized) {
                val state = sessionManager.sessionState.value
                if (state.sessionId != null && !state.isHost && state.hostOnlyMode) {
                    android.util.Log.d("MusicSync", "SessionCallback: Blocking skipNext (Host-Only Mode)")
                    return
                }
            }
            runCatching(player::forceSeekToNext)
        }
        override fun onSeekTo(pos: Long) {
            if (::sessionManager.isInitialized) sessionManager.seekTo(pos)
            else player.seekTo(pos)
        }
        override fun onStop() {
            if (::sessionManager.isInitialized) sessionManager.pause()
            else player.pause()
        }
        override fun onRewind() = player.seekToDefaultPosition()
        override fun onSkipToQueueItem(id: Long) =
            runCatching { player.seekToDefaultPosition(id.toInt()) }.let { }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            if (action == FAVORITE_ACTION) likeAction()
        }
    }

    private inner class NotificationActionReceiver(private val player: Player) :
        BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d("NotificationAction", "Broadcast received! Action: ${intent.action}")
            when (intent.action) {
                Action.pause.value -> {
                    android.util.Log.d("NotificationAction", "PAUSE action triggered")
                    android.util.Log.d("NotificationAction", "Player state before pause: isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}")
                    
                    if (::sessionManager.isInitialized) {
                        android.util.Log.d("NotificationAction", "SessionManager initialized, calling sessionManager.pause()")
                        sessionManager.pause()
                    } else {
                        android.util.Log.d("NotificationAction", "SessionManager NOT initialized, calling player.pause()")
                        player.pause()
                    }
                    
                    android.util.Log.d("NotificationAction", "Player state after pause: isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}")
                }
                Action.play.value -> {
                    android.util.Log.d("NotificationAction", "PLAY action triggered")
                    android.util.Log.d("NotificationAction", "Player state before play: isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}")
                    play()
                    android.util.Log.d("NotificationAction", "Player state after play: isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}")
                }
                Action.next.value -> {
                    android.util.Log.d("NotificationAction", "NEXT action triggered")
                    binder.syncSkipNext()
                }
                Action.previous.value -> {
                    android.util.Log.d("NotificationAction", "PREVIOUS action triggered")
                    binder.syncSkipPrevious()
                }
                else -> {
                    android.util.Log.w("NotificationAction", "Unknown action: ${intent.action}")
                }
            }
        }
    }

    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            context.stopService(context.intent<PlayerService>())
        }
    }

    @JvmInline
    private value class Action(val value: String) {
        context(Context)
        val pendingIntent: PendingIntent
            get() = PendingIntent.getBroadcast(
                this@Context,
                requestCode,
                Intent(value).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT.or(if (isAtLeastAndroid6) PendingIntent.FLAG_IMMUTABLE else 0)
            )

        private val requestCode: Int
            get() = when (this.value) {
                pause.value -> 100
                play.value -> 101
                next.value -> 102
                previous.value -> 103
                else -> 100
            }

        companion object {
            val pause = Action("com.github.musicyou.pause")
            val play = Action("com.github.musicyou.play")
            val next = Action("com.github.musicyou.next")
            val previous = Action("com.github.musicyou.previous")
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "default_channel_id"

        const val SLEEP_TIMER_NOTIFICATION_ID = 1002
        const val SLEEP_TIMER_NOTIFICATION_CHANNEL_ID = "sleep_timer_channel_id"

        const val FAVORITE_ACTION = "FAVORITE"
    }
}
