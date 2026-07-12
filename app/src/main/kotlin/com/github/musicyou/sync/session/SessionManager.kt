package com.github.musicyou.sync.session

import android.util.Log
import com.github.musicyou.sync.playback.PlaybackEngine
import com.github.musicyou.sync.protocol.*
import com.github.musicyou.sync.time.ClockState
import com.github.musicyou.sync.time.TimeSyncEngine
import com.github.musicyou.sync.transport.TransportLayer
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.sync.presence.PresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The Central Authority.
 * Orchestrates TimeSync and Playback.
 * Enforces "Host Write / Participant Read" policy.
 */
class SessionManager(
    val context: android.content.Context, // Injected context for MediaStore access
    val timeSyncEngine: TimeSyncEngine,
    val playbackEngine: PlaybackEngine,
    private val transportLayer: TransportLayer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "MusicSync"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L  // Send heartbeat every 5 seconds to keep connection alive
        private const val SYNC_ECHO_SUPPRESS_MS = 3000L 
        private const val SYNC_LEAD_TIME_MS = 0L       // 4s lead for snapshot scheduling
        private const val PARTICIPANT_LEAD_TIME_MS = 400L // 400ms participant lead vs host (Balance between sync tightness and lag buffer)
        private const val DRIFT_THRESHOLD_MS = 300L      // 300ms drift threshold for snap-to-sync
        private const val DRIFT_CHECK_INTERVAL_MS = 2000L // 2s check interval for responsive drift detection
        private const val DEBOUNCE_MS = 300L
        
        // FIX 2: Extended snapshot lock duration to cover async ExoPlayer callbacks
        private const val SNAPSHOT_LOCK_DURATION_MS = 1500L
        
        // FIX 3: Event deduplication thresholds
        private const val DEDUP_THRESHOLD_MS = 200L
        private const val POSITION_DRIFT_THRESHOLD_MS = 300L
        
        // FIX 5: Host-side coalescing window
        private const val COALESCE_WINDOW_MS = 200L
        
        // PHASE 2: Heartbeat validation constants
        private const val MAX_MISSED_HEARTBEATS = 3  // 15s timeout (3 × 5s interval)
        private const val PONG_TIMEOUT_MS = 2000L    // 2s wait for each pong
    }

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // SOCIAL: Flow for real-time interactions (reactions, ripples, messages)
    // Replay 1 ensures late-joining UI collectors (like after a rotation or binder reconnect) don't miss the latest splash.
    private val _socialEvents = MutableSharedFlow<SyncEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val socialEvents: SharedFlow<SyncEvent> = _socialEvents.asSharedFlow()

    private var isHost = false
    private var eventBroadcaster: ((SyncEvent) -> Unit)? = null
    
    /**
     * Callback to get current track metadata (title, artist, thumbnailUrl).
     * This is called when broadcasting sync events to include metadata for UI display.
     */
    private var metadataProvider: (() -> Triple<String?, String?, String?>)? = null
    
    /**
     * Callback to get the current user's name for sync identification.
     */
    private var nameProvider: (() -> String?)? = null
    
    /**
     * Callback to get the current user's avatar URL.
     */
    private var avatarProvider: (() -> String?)? = null
    
    /**
     * Callback to show toast notifications (used on Host when participant requests changes).
     */
    private var toastHandler: ((String) -> Unit)? = null
    
    // Re-entrancy guard to prevent seek loop
    private var isSeeking = false
    
    // Flag to distinguish sync-triggered track changes from user-initiated ones
    // When true, onTrackChanged should NOT broadcast (to prevent broadcast storms)
    private var isApplyingSnapshot = false
    
    // Heartbeat job to keep connection alive
    private var heartbeatJob: Job? = null
    private var driftMonitorJob: Job? = null
    
    // Track the last synced media ID and timestamp to prevent echo-back after async load
    // When participant receives sync event, track loads asynchronously. By the time
    // onMediaItemTransition callback fires, isApplyingSnapshot is already false.
    // This tracks what we synced to detect and suppress echo broadcasts.
    private var lastSyncedMediaId: String? = null
    private var lastSyncedTimestamp: Long = 0L
    
    // Debouncing for playback controls to prevent rapid button spam
    private var lastResumeTime: Long = 0L
    private var lastPauseTime: Long = 0L
    private var lastSeekTime: Long = 0L
    
    // Rate limiting for social interactions
    private var lastReactionTime: Long = 0L
    private var lastFlashMessageTime: Long = 0L
    private var lastKineticTouchTime: Long = 0L
    
    // FIX 3: Event deduplication tracking
    private var lastAppliedMediaId: String? = null
    private var lastAppliedStatus: SessionState.Status? = null
    private var lastAppliedPosition: Long = 0L
    private var lastAppliedTimestamp: Long = 0L
    
    // Deduplication cache for social events to prevent echo
    private val processedSocialEvents = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 100
            }
        }
    )
    
    // FIX 4: Version control
    private var hostStateVersion: Long = 0L
    private var lastAppliedVersion: Long = 0L
    
    // FIX 5: Host-side coalescing job
    private var pendingBroadcastJob: Job? = null
    
    // CRITICAL FIX: Snapshot Application Lock (replaces time-based delay)
    // Prevents concurrent snapshot applies and guarantees newer snapshots are never blocked
    private val applyLock = Mutex()
    private var currentApplyingVersion: Long? = null
    
    // PHASE 2: Transport Heartbeat Validation (prevents ghost sync states)
    // Tracks missed pongs to detect silent network disconnects
    private var missedHeartbeats = 0
    private val pendingPongs = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    
    // Throttling for sync requests
    private var lastSyncRequestTime: Long = 0L

    init {
        Log.d(TAG, "init: SessionManager created")
        
        // CRITICAL: Establish baseline presence (online/offline tracking)
        PresenceManager.connect()
        Log.i(TAG, "init: PresenceManager.connect() called - online presence established")
        
        // Observe TimeSyncEngine for RTT updates
        timeSyncEngine.clockState.onEach { clock ->
             if (clock.rtt > 0) {
                 _sessionState.update { it.copy(ping = clock.rtt) }
             }
        }.launchIn(scope)

        // Sync transport layer's sessionId and connectedPeers to sessionState
        transportLayer.sessionId.onEach { sessionId ->
            Log.d(TAG, "init: sessionId changed to: $sessionId")
            _sessionState.update { current ->
                current.copy(
                    sessionId = sessionId,
                    isHandshaking = sessionId == null && current.isHandshaking
                )
            }
            
            // RTDB INTEGRATION: Join session when sessionId is established
            if (sessionId != null) {
                Log.i(TAG, "init: RTDB - Joining session=$sessionId as ${if (isHost) "HOST" else "PARTICIPANT"}")
                PresenceManager.joinSession(sessionId, isHost = isHost)
                // NOTE: updateStatus() is called INSIDE joinSession() after successful member add
            } else {
                Log.i(TAG, "init: RTDB - Leaving session (sessionId null)")
                PresenceManager.leaveSession()
                // leaveSession() already calls updateStatus("idle", null)
            }
            
            // STRICT RULE: If Host disconnects (sessionId null), Participant must stop playback.
            if (sessionId == null && !isHost) {
                Log.i(TAG, "init: Session ended (disconnected). Stopping local playback.")
                playbackEngine.pause()
                playbackEngine.seekTo(0)
            }
        }.launchIn(scope)
        
        
        // RTDB INTEGRATION: Observe RTDB session members instead of P2P transport
        PresenceManager.currentSessionMembers.onEach { rtdbMembers ->
            Log.i(TAG, "init: RTDB members changed: $rtdbMembers (count=${rtdbMembers.size})")
            
            // Convert UID set to peer ID set (for now, we'll use UIDs as peer IDs)
            // In the future, we might need a mapping
            val peers = rtdbMembers
            val previousPeerCount = _sessionState.value.connectedPeers.size
            
            // Update connected peers and sync status
            _sessionState.update { current ->
                val previousPeerNames = current.connectedPeerNames.toMutableMap()
                val previousPeerUids = current.connectedPeerUids.toMutableMap()
                
                // Add "Connecting..." placeholders for any NEW peer IDs that don't have names yet
                peers.forEach { peerId ->
                    if (!previousPeerNames.containsKey(peerId)) {
                        Log.d(TAG, "Instant Reactivity: Adding placeholder for $peerId")
                        previousPeerNames[peerId] = "Connecting..."
                    }
                    // FIX: Populate UID so ParticipantRow can fetch profile logic
                    if (!previousPeerUids.containsKey(peerId)) {
                        previousPeerUids[peerId] = peerId
                    }
                }
                
                val previousPeerAvatars = current.connectedPeerAvatars.toMutableMap()
                
                // Cleanup: Remove IDs that are no longer in the transport layer's peer list
                // We convert values to a list to avoid ConcurrentModificationException if we were iterating over the map itself
                val currentPeerIds = peers.toSet()
                // HYBRID FIX: Don't cleanup if peer is still connected via Transport Layer (P2P)
                // This prevents "ghost disconnects" when RTDB drops but P2P is alive
                val transportIds = transportLayer.connectedPeers.value.toSet()
                val keysToCleanup = previousPeerNames.keys.filter { 
                    it != "local-user" && !currentPeerIds.contains(it) && !transportIds.contains(it) && !it.startsWith("name:") 
                }
                keysToCleanup.forEach { 
                    Log.d(TAG, "Instant Reactivity: Cleaning up disconnected peer $it")
                    previousPeerNames.remove(it) 
                    previousPeerAvatars.remove(it)
                    previousPeerUids.remove(it)
                }

                current.copy(
                    connectedPeers = peers.toSet(),
                    connectedPeerNames = previousPeerNames,
                    connectedPeerAvatars = previousPeerAvatars,
                    connectedPeerUids = previousPeerUids,
                    // UX: Host shows READY when peers connect (via RTDB or Transport), back to WAITING if empty
                    syncStatus = if (isHost && (peers.isNotEmpty() || transportLayer.connectedPeers.value.isNotEmpty()))
                        SessionState.SyncStatus.READY
                    else if (isHost)
                        SessionState.SyncStatus.WAITING
                    else
                        current.syncStatus,
                    clockSyncMessage = if (isHost && peers.isNotEmpty())
                        "${peers.size} participant(s) connected! Ready to sync."
                    else if (isHost)
                        "Waiting for participants to join..."
                    else
                        current.clockSyncMessage
                )
            }
            
            // HOST: SEAMLESS SYNC ON JOIN (RTDB TRIGGER)
            if (isHost && peers.size > previousPeerCount && peers.isNotEmpty()) {
                Log.i(TAG, "init: RTDB detected new peer - calling initiateSeamlessSync")
                
                // Launch in separate coroutine to avoid blocking collector
                scope.launch {
                    initiateSeamlessSync(
                        triggerSource = "RTDB Member Join",
                        isFirstJoin = previousPeerCount == 0
                    )
                }
            }
            
            // PARTICIPANT: When connecting to host, send join announcement and request state
            if (!isHost && peers.isNotEmpty() && previousPeerCount == 0) {
                // Send JoinEvent to announce our name
                val userName = getCurrentUserName()
                val userAvatar = getCurrentAvatar()
                val finalName = userName ?: android.os.Build.MODEL
                val myUid = ProfileManager.getCurrentUserUid()
                Log.i(TAG, "init: Participant sending JoinEvent (name=$finalName, avatar=$userAvatar, uid=$myUid)")
                val joinEvent = JoinEvent(name = finalName, avatar = userAvatar, uid = myUid, timestamp = timeSyncEngine.getGlobalTime())
                eventBroadcaster?.invoke(joinEvent)
                
                // Request the current state
                val requestEvent = RequestStateEvent(
                    timestamp = timeSyncEngine.getGlobalTime(),
                    senderName = finalName,
                    senderAvatar = userAvatar,
                    senderUid = myUid
                )
                eventBroadcaster?.invoke(requestEvent)
            }
        }.launchIn(scope)

        // RESYNC FIX: Listen to TransportLayer connections for P2P-level reconnects
        var previousTransportPeerCount = 0
        transportLayer.connectedPeers.onEach { transportPeers ->
             Log.d(TAG, "DEBUG: transportLayer.connectedPeers emitted: size=${transportPeers.size}, previous=$previousTransportPeerCount, isHost=$isHost")
             
             if (isHost && transportPeers.size > previousTransportPeerCount && transportPeers.isNotEmpty()) {
                Log.i(TAG, "init: Transport detected new peer connection (${transportPeers.size} peers) - calling initiateSeamlessSync")
                
                // Trigger sync for the reconnected peer
                initiateSeamlessSync(
                    triggerSource = "Transport P2P Connect",
                    isFirstJoin = previousTransportPeerCount == 0
                )
            }
            
            // PARTICIPANT FIX: Trigger state request when P2P connection is established (Race Condition Fix)
            // Previously only triggered on RTDB update, which could happen before Transport was ready
            if (!isHost && transportPeers.isNotEmpty() && previousTransportPeerCount == 0) {
                Log.i(TAG, "init: Transport connected to Host - Sending Join+RequestState (Race Condition Fix)")
                val userName = getCurrentUserName()
                val userAvatar = getCurrentAvatar()
                val finalName = userName ?: android.os.Build.MODEL
                val myUid = ProfileManager.getCurrentUserUid()
                
                // 1. Send JoinEvent
                val joinEvent = JoinEvent(name = finalName, avatar = userAvatar, uid = myUid, timestamp = timeSyncEngine.getGlobalTime())
                eventBroadcaster?.invoke(joinEvent)
                
                // 2. Request State
                val requestEvent = RequestStateEvent(
                    timestamp = timeSyncEngine.getGlobalTime(),
                    senderName = finalName,
                    senderAvatar = userAvatar,
                    senderUid = myUid
                )
                eventBroadcaster?.invoke(requestEvent)
            }
            previousTransportPeerCount = transportPeers.size
        }.launchIn(scope)
        
        // CORE FIX: Listen for Host UID changes from RTDB
        // This ensures Guest always knows who the host is, even if P2P sync is partial
        PresenceManager.currentSessionHost.onEach { hostUid ->
             if (hostUid != null) {
                 Log.i(TAG, "init: RTDB - Host UID updated to $hostUid")
                 _sessionState.update { it.copy(hostUid = hostUid) }
             }
        }.launchIn(scope)
        
        // CORE FIX: Listen for playback state changes and broadcast when host is connected
        var lastIsPlaying: Boolean? = null
        var lastMediaId: String? = null
        var lastPosition: Long = 0L
        var lastPlaybackSpeed: Float = 1.0f
        
        playbackEngine.playbackState.onEach { state ->
            // HOST LOGIC: Detect local media and generate fingerprint
            if (isHost && !isApplyingSnapshot) {
                // Check if current media is local
                val mediaId = state.mediaId
                if (mediaId != null && (mediaId.startsWith("content://") || mediaId.startsWith("file://"))) {
                    // Extract fingerprint
                     Log.d("MusicSyncFlow", "Host: Local media detected ($mediaId). Generating fingerprint...")
                     val fingerprint = com.github.musicyou.utils.DeviceMediaManager.getFingerprintFromUri(context, mediaId)
                     
                     if (fingerprint != null) {
                         if (_sessionState.value.mediaFingerprint != fingerprint) {
                             Log.i("MusicSyncFlow", "Host: Fingerprint generated: ${fingerprint.title} (${fingerprint.durationMs}ms)")
                             _sessionState.update { it.copy(mediaFingerprint = fingerprint) }
                         } else {
                             // Log.v("MusicSyncFlow", "Host: Fingerprint already set")
                         }
                     } else {
                         Log.e("MusicSyncFlow", "Host: Failed to generate fingerprint for $mediaId")
                     }
                } else if (_sessionState.value.mediaFingerprint != null) {
                    // Clear fingerprint if we switched to non-local media
                    _sessionState.update { it.copy(mediaFingerprint = null) }
                }
            }
            // FIX 1: CRITICAL - Suppress ALL auto-broadcasts during snapshot application
            if (isApplyingSnapshot) {
                Log.d(TAG, "Auto-broadcast: SUPPRESSED (applying snapshot)")
                // Still update tracking variables to avoid false delta detection after unlock
                lastIsPlaying = state.isPlaying
                lastMediaId = state.mediaId
                lastPosition = state.currentPositionMs
                return@onEach
            }
            
            // Only broadcast if we are host with connected peers (RTDB or Transport)
            // HYBRID FIX: Check TransportLayer directly to maintain sync even if RTDB flickers
            val hasTransportPeers = transportLayer.connectedPeers.value.isNotEmpty()
            val hasRtdbPeers = _sessionState.value.connectedPeers.isNotEmpty()
            val hasPeers = hasRtdbPeers || hasTransportPeers
            
            if (isHost) {
                val now = timeSyncEngine.getGlobalTime()
                
                // Detect play/pause change
                if (lastIsPlaying != state.isPlaying) {
                    if (state.isPlaying) {
                        Log.i(TAG, "Host state: resumed play")
                        val (title, artist, thumbnailUrl) = getCurrentMetadata()
                        val event = PlayEvent(
                            mediaId = state.mediaId ?: "",
                            startPos = state.currentPositionMs,
                            timestamp = now,
                            title = title,
                            artist = artist,
                            thumbnailUrl = thumbnailUrl,
                            requesterName = getCurrentUserName(),
                            requesterAvatar = getCurrentAvatar(),
                            mediaFingerprint = _sessionState.value.mediaFingerprint // Include fingerprint
                        )
                        if (hasPeers) eventBroadcaster?.invoke(event)
                        
                        _sessionState.update { current ->
                            current.copy(
                                currentMediaId = state.mediaId,
                                playbackStatus = SessionState.Status.PLAYING,
                                trackStartGlobalTime = now,
                                positionAtAnchor = state.currentPositionMs,
                                title = title,
                                artist = artist,
                                thumbnailUrl = thumbnailUrl
                            )
                        }
                    } else {
                        Log.i(TAG, "Host state: paused")
                        val event = PauseEvent(
                            pos = state.currentPositionMs,
                            timestamp = now,
                            requesterName = getCurrentUserName(),
                            requesterAvatar = getCurrentAvatar()
                        )
                        if (hasPeers) eventBroadcaster?.invoke(event)
                        
                        _sessionState.update { current ->
                            current.copy(
                                playbackStatus = SessionState.Status.PAUSED,
                                positionAtAnchor = state.currentPositionMs
                            )
                        }
                    }
                }
                
                // Detect track change - broadcast as PlayEvent to sync the new track
                if (lastMediaId != state.mediaId && state.mediaId != null) {
                    Log.i(TAG, "Host state: changed track to ${state.mediaId}")
                    val (title, artist, thumbnailUrl) = getCurrentMetadata()
                    val event = PlayEvent(
                        mediaId = state.mediaId,
                        startPos = state.currentPositionMs,
                        timestamp = now,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl,
                        requesterName = getCurrentUserName(),
                        requesterAvatar = getCurrentAvatar(),
                        mediaFingerprint = _sessionState.value.mediaFingerprint // Include fingerprint
                    )
                    if (hasPeers) eventBroadcaster?.invoke(event)
                    
                    _sessionState.update { current ->
                        current.copy(
                            currentMediaId = state.mediaId,
                            title = title,
                            artist = artist,
                            thumbnailUrl = thumbnailUrl,
                            positionAtAnchor = state.currentPositionMs
                        )
                    }
                }
                
                // Detect significant seek (more than 2 seconds difference)
                val positionDiff = kotlin.math.abs(state.currentPositionMs - lastPosition)
                if (!isSeeking && lastIsPlaying == state.isPlaying && positionDiff > 2000) {
                    Log.i(TAG, "Host state: seeked to ${state.currentPositionMs}")
                    val event = SeekEvent(
                        pos = state.currentPositionMs,
                        timestamp = now,
                        requesterName = getCurrentUserName(),
                        requesterAvatar = getCurrentAvatar()
                    )
                    if (hasPeers) eventBroadcaster?.invoke(event)
                    
                    _sessionState.update { current ->
                        current.copy(
                            positionAtAnchor = state.currentPositionMs,
                            trackStartGlobalTime = now
                        )
                    }
                }

                // Detect speed change
                if (lastPlaybackSpeed != state.playbackSpeed) {
                    Log.i(TAG, "Host state: changed speed to ${state.playbackSpeed}x")
                    val (title, artist, thumbnailUrl) = getCurrentMetadata()
                    // Re-broadcast as PlayEvent with new speed (effectively a "Play at X speed" command)
                    val event = PlayEvent(
                        mediaId = state.mediaId ?: "",
                        startPos = state.currentPositionMs,
                        timestamp = now,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl,
                        requesterName = getCurrentUserName(),
                        requesterAvatar = getCurrentAvatar(),
                        mediaFingerprint = _sessionState.value.mediaFingerprint,
                        playbackSpeed = state.playbackSpeed
                    )
                    if (hasPeers) eventBroadcaster?.invoke(event)
                    
                    _sessionState.update { current ->
                        current.copy(
                            playbackSpeed = state.playbackSpeed,
                            positionAtAnchor = state.currentPositionMs,
                            trackStartGlobalTime = now // Speed change resets the anchor time for drift calc
                        )
                    }
                }
            }
            
            // Update tracking variables
            lastIsPlaying = state.isPlaying
            lastMediaId = state.mediaId
            lastPosition = state.currentPositionMs
            lastPlaybackSpeed = state.playbackSpeed
        }.launchIn(scope)
    }

    fun setEventBroadcaster(broadcaster: (SyncEvent) -> Unit) {
        Log.d(TAG, "setEventBroadcaster: broadcaster set")
        this.eventBroadcaster = broadcaster
    }
    
    /**
     * Set metadata provider for sync events.
     * @param provider Returns Triple(title, artist, thumbnailUrl) for current track.
     */
    fun setMetadataProvider(provider: () -> Triple<String?, String?, String?>) {
        Log.d(TAG, "setMetadataProvider: provider set")
        this.metadataProvider = provider
    }
    
    /**
     * Set name provider for sync events.
     * Returns the current user's display name.
     */
    fun setNameProvider(provider: () -> String?) {
        Log.d(TAG, "setNameProvider: provider set")
        this.nameProvider = provider
    }
    
    /**
     * Set avatar provider for sync events.
     * Returns the current user's avatar URL.
     */
    fun setAvatarProvider(provider: () -> String?) {
        Log.d(TAG, "setAvatarProvider: provider set")
        this.avatarProvider = provider
    }
    
    /**
     * Set toast handler for participant action notifications.
     * Called on Host when participant requests changes.
     */
    fun setToastHandler(handler: (String) -> Unit) {
        Log.d(TAG, "setToastHandler: handler set")
        this.toastHandler = handler
    }
    
    /**
     * Get current metadata from provider.
     */
    private fun getCurrentMetadata(): Triple<String?, String?, String?> {
        return metadataProvider?.invoke() ?: Triple(null, null, null)
    }
    
    /**
     * Get current user's name from provider.
     */
    private fun getCurrentUserName(): String? {
        return nameProvider?.invoke()
    }
    
    /**
     * Get current user's avatar URL from provider.
     */
    private fun getCurrentAvatar(): String? {
        return avatarProvider?.invoke()
    }
    
    /**
     * UNIFIED LEAD TIME CALCULATOR
     * Calculates the correct playback position for this device, accounting for participant lead time.
     * 
     * Host: Returns base position (no lead time adjustment)
     * Participant: Returns base position + elapsed time + 450ms lead
     * 
     * This centralizes all lead time logic to ensure consistency across:
     * - Snapshot application position calculation
     * - Scheduled track load positions
     * - Drift monitor expected positions
     * 
     * @param anchorPos The playback position at the anchor time (ms)
     * @param anchorTime The global timestamp when anchorPos was valid (ms)
     * @param speed Playback speed multiplier (default 1.0)
     * @return The position this device should be at right now (ms)
     */
    private fun calculateParticipantPosition(
        anchorPos: Long,
        anchorTime: Long,
        speed: Float = 1.0f
    ): Long {
        if (isHost) {
            // Host has no lead time adjustment - return position as-is
            val now = timeSyncEngine.getGlobalTime()
            val elapsedSinceAnchor = now - anchorTime
            return anchorPos + (elapsedSinceAnchor * speed).toLong()
        } else {
            // Participant runs ahead by PARTICIPANT_LEAD_TIME_MS to compensate for latency
            val now = timeSyncEngine.getGlobalTime()
            val elapsedSinceAnchor = now - anchorTime
            val basePos = anchorPos + (elapsedSinceAnchor * speed).toLong()
            return basePos + PARTICIPANT_LEAD_TIME_MS
        }
    }
    
    /**
     * Start periodic heartbeat to keep connection alive.
     * PHASE 2 ENHANCEMENT: Now validates pong responses to detect silent disconnects.
     * Tracks ping/pong correlation and triggers auto-disconnect after 3 missed pongs.
     */
    private fun requestFullSync(reason: String) {
        val now = timeSyncEngine.getGlobalTime()
        Log.w(TAG, "requestFullSync: Requesting fresh state from Host. Reason=$reason")
        
        // Prevent spamming requests
        if (System.currentTimeMillis() - lastSyncRequestTime < 5000L) {
             Log.d(TAG, "requestFullSync: Throttled (too soon)")
             return
        }
        lastSyncRequestTime = System.currentTimeMillis()
        
        val req = RequestStateEvent(
            timestamp = now,
            senderName = getCurrentUserName(),
            senderAvatar = getCurrentAvatar(),
            senderUid = ProfileManager.getCurrentUserUid()
        )
        eventBroadcaster?.invoke(req)
        
        // UX Feedback
        toastHandler?.invoke("Resyncing connection...")
        _sessionState.update { it.copy(clockSyncMessage = "Resyncing...") }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            Log.i(TAG, "startHeartbeat: Starting periodic heartbeat with validation (${HEARTBEAT_INTERVAL_MS}ms interval)")
            missedHeartbeats = 0  // Reset counter
            
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_sessionState.value.sessionId != null) {
                    val now = timeSyncEngine.getGlobalTime()
                    val pingId = "heartbeat-${System.currentTimeMillis()}"
                    
                    // Host populates authoritative state for validation
                    val mediaId = if (isHost) _sessionState.value.currentMediaId else null
                    val status = if (isHost) _sessionState.value.playbackStatus else SessionState.Status.PAUSED
                    // Use engine directly for pos if host (main thread safe here in coroutine)
                    val pos = if (isHost) playbackEngine.getCurrentPosition() else 0L
                    
                    val ping = PingEvent(
                        id = pingId, 
                        clientTimestamp = System.currentTimeMillis(), // FIX: Use RAW local time for correct RTT calculation
                        timestamp = now,
                        currentMediaId = mediaId,
                        currentPos = pos,
                        playbackStatus = status
                    )
                    
                    // PHASE 2 UX FIX: Only validate heartbeat if we have connected peers
                    // This prevents host from auto-disconnecting while waiting for participants to join
                    val hasPeers = _sessionState.value.connectedPeers.isNotEmpty()
                    
                    if (hasPeers) {
                        // Have peers - validate pong responses (3-strike disconnect)
                        val pongReceived = CompletableDeferred<Boolean>()
                        pendingPongs[pingId] = pongReceived
                        
                        // Send ping
                        Log.d(TAG, "heartbeat: Sending ping $pingId (missed: $missedHeartbeats/${MAX_MISSED_HEARTBEATS})")
                        eventBroadcaster?.invoke(ping)
                        
                        // Wait for pong with timeout
                        val success = withTimeoutOrNull(PONG_TIMEOUT_MS) {
                            pongReceived.await()
                            true
                        } != null
                        
                        // Clean up pending pong regardless of result
                        pendingPongs.remove(pingId)
                        
                        if (success) {
                            // Pong received - reset failure counter
                            if (missedHeartbeats > 0) {
                                Log.i(TAG, "heartbeat: Pong received for $pingId (connection recovered)")
                            }
                            missedHeartbeats = 0
                        } else {
                            // Missed pong - increment failure counter
                            missedHeartbeats++
                            Log.w(TAG, "heartbeat: MISSED pong for $pingId (${missedHeartbeats}/${MAX_MISSED_HEARTBEATS})")
                            
                            if (missedHeartbeats >= MAX_MISSED_HEARTBEATS) {
                                Log.e(TAG, "heartbeat: Connection lost after $missedHeartbeats missed pongs. Disconnecting...")
                                
                                // Update UI with error message
                                _sessionState.update { 
                                    it.copy(
                                        syncStatus = SessionState.SyncStatus.ERROR,
                                        clockSyncMessage = "Connection lost. Session ended."
                                    )
                                }
                                
                                // Trigger clean disconnect
                                stopSession()
                                break  // Exit heartbeat loop
                            }
                        }
                    } else {
                        // No peers yet - send ping but don't validate (host waiting for participants)
                        Log.d(TAG, "heartbeat: Sending ping $pingId (no validation - waiting for participants)")
                        eventBroadcaster?.invoke(ping)
                        // Reset counter to ensure clean state when first peer joins
                        missedHeartbeats = 0
                    }
                }
            }
        }
    }
    
    /**
     * Stop periodic heartbeat.
     */
    private fun stopHeartbeat() {
        Log.i(TAG, "stopHeartbeat: Stopping heartbeat")
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun stopDriftMonitor() {
        if (driftMonitorJob != null) {
            Log.i(TAG, "stopDriftMonitor: Stopping drift monitor")
            driftMonitorJob?.cancel()
            driftMonitorJob = null
        }
    }
    
    /**
     * RAPID SYNC: Send burst of pings on participant join for fast clock calibration.
     * Sends RAPID_SYNC_COUNT pings at RAPID_SYNC_INTERVAL_MS intervals.
     * This quickly builds up samples in TimeSyncEngine for Perfect Initial Sync.
     */
    private fun startRapidSync() {
        if (isHost) return // Host doesn't need to sync clock
        
        // UX: Set SYNCING status during calibration
        _sessionState.update {
            it.copy(
                syncStatus = SessionState.SyncStatus.SYNCING,
                clockSyncMessage = "Syncing clocks... (0/5)"
            )
        }
        
        scope.launch {
            Log.i(TAG, "startRapidSync: Starting rapid clock sync (5 pings at 500ms intervals)")
            repeat(5) { i ->
                delay(500) // 500ms between pings
                if (_sessionState.value.sessionId != null) {
                    val now = System.currentTimeMillis() // Use local time for ping
                    val pingId = "rapid-sync-$i-${System.currentTimeMillis()}"
                    val ping = PingEvent(id = pingId, clientTimestamp = now, timestamp = now)
                    Log.d(TAG, "rapidSync: Sending ping $pingId (${i + 1}/5)")
                    eventBroadcaster?.invoke(ping)
                    
                    // UX: Update progress
                    _sessionState.update {
                        it.copy(
                            clockSyncMessage = "Syncing clocks... (${i + 1}/5)"
                        )
                    }
                }
            }
            
            // UX: Set READY status after calibration
            _sessionState.update {
                it.copy(
                    syncStatus = SessionState.SyncStatus.READY,
                    clockSyncMessage = "Clock synced! Ready for playback."
                )
            }
            Log.i(TAG, "startRapidSync: Rapid sync complete, clock calibrated, status=READY")
        }
    }
    
    /**
     * FIX 5: HOST-SIDE COALESCING
     * Debounced broadcast that merges rapid state changes into ONE StateSyncEvent.
     * Cancels any pending broadcast and schedules a new one after COALESCE_WINDOW_MS.
     */
    private fun broadcastAuthoritativeState() {
        if (!isHost) return
        
        pendingBroadcastJob?.cancel()
        pendingBroadcastJob = scope.launch {
            delay(COALESCE_WINDOW_MS)
            hostStateVersion++
            val (title, artist, thumbnailUrl) = getCurrentMetadata()
            val state = _sessionState.value.copy(
                stateVersion = hostStateVersion,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl
            )
            val now = timeSyncEngine.getGlobalTime()
            Log.i(TAG, "broadcastAuthoritativeState: Sending coalesced StateSyncEvent v$hostStateVersion")
            eventBroadcaster?.invoke(StateSyncEvent(state, now))
        }
    }
    
    /**
     * PERFECT INITIAL SYNC: Wait until scheduled global time before starting playback.
     * All devices wait together, then start at exactly the same moment.
     * 
     * @param scheduledTime The global time when playback should start
     */
    /**
     * SEAMLESS SYNC: HOST -> PARTICIPANT
     * Sends current playback state to newly joined participants.
     */
    private suspend fun initiateSeamlessSync(triggerSource: String, isFirstJoin: Boolean) {
        if (!isHost) return
        
        Log.i(TAG, "initiateSeamlessSync: Triggered by $triggerSource")
        
        // Small delay to ensure participant is ready to receive
        kotlinx.coroutines.delay(200)
        
        val engine = playbackEngine.playbackState.value
        val wasPlaying = engine.isPlaying
        val currentPos = engine.currentPositionMs
        val currentMediaId = engine.mediaId
        
        // HOST KEEPS PLAYING (or starts now if it was the first join)
        val shouldAutoPlay = isFirstJoin
        val targetPlaybackStatus = if (wasPlaying || shouldAutoPlay) SessionState.Status.PLAYING else SessionState.Status.PAUSED
        
        // Calculate scheduled time and where host WILL BE at that time
        val now = timeSyncEngine.getGlobalTime()
        val scheduledStartTime = now + SYNC_LEAD_TIME_MS
        
        // Target position = where host will be after lead time
        val isEffectivelyPlaying = wasPlaying || shouldAutoPlay
        
        // FIX: If first participant join, force start from 0:00 for synchronized start
        val targetPosition = if (isFirstJoin) {
            0L 
        } else if (isEffectivelyPlaying) {
            currentPos + SYNC_LEAD_TIME_MS
        } else {
            currentPos
        }
        
        // Get current metadata
        val (title, artist, thumbnailUrl) = getCurrentMetadata()
        Log.d("MusicSyncFlow", "initiateSeamlessSync: [HOST] Retrieved initial metadata: Title='$title', Artist='$artist', Art='$thumbnailUrl'")
        
        // Update state with scheduled sync info
        _sessionState.update { current ->
            current.copy(
                currentMediaId = currentMediaId,
                playbackStatus = targetPlaybackStatus,
                trackStartGlobalTime = scheduledStartTime,
                positionAtAnchor = targetPosition, 
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                clockSyncMessage = if (isEffectivelyPlaying) "Participant syncing..." else "Ready"
            )
        }
        
        // Broadcast state - participant will preload and sync
        val syncEvent = StateSyncEvent(_sessionState.value, now)
        Log.i(TAG, "initiateSeamlessSync: Broadcasting - scheduledStart=$scheduledStartTime, targetPos=$targetPosition (auto-play=$shouldAutoPlay)")
        eventBroadcaster?.invoke(syncEvent)
        
        // Host starts playing if it was requested to auto-play
        if (shouldAutoPlay && !wasPlaying) {
            Log.i(TAG, "initiateSeamlessSync: HOST starting auto-play for first participant")
            
            // FIX: If first join, also seek host to 0 to match participant
            if (isFirstJoin) {
                Log.i(TAG, "initiateSeamlessSync: First join - seeking host to 0")
                playbackEngine.seekTo(0)
            }
            
            playbackEngine.play()
        } else if (isFirstJoin) {
             // Even if already playing (unlikely given logic above), seek to 0 if first join
             Log.i(TAG, "initiateSeamlessSync: First join (wasPlaying=$wasPlaying) - seeking host to 0")
             playbackEngine.seekTo(0)
        }

        // Update message for UX
        if (isEffectivelyPlaying) {
            _sessionState.update { it.copy(clockSyncMessage = "Playing in sync! 🎵") }
        }
    }

    private suspend fun waitForScheduledTime(scheduledTime: Long) {
        val now = timeSyncEngine.getGlobalTime()
        val waitMs = scheduledTime - now
        if (waitMs > 0) {
            Log.i(TAG, "waitForScheduledTime: Waiting ${waitMs}ms until scheduled start")
            delay(waitMs)
            Log.i(TAG, "waitForScheduledTime: Wait complete, starting now!")
        } else {
            Log.d(TAG, "waitForScheduledTime: Scheduled time already passed (diff=${waitMs}ms), starting immediately")
        }
    }


    // ============================================================
    // HOST CONTROLS
    // ============================================================

    fun startSession() {
        Log.i(TAG, "startSession: Starting as HOST")
        isHost = true
        
        // UX Requirement: Pause on start
        playbackEngine.pause()
        
        val now = timeSyncEngine.getGlobalTime()
        val engine = playbackEngine.playbackState.value
        Log.d(TAG, "startSession: Current playback - mediaId=${engine.mediaId}, pos=${engine.currentPositionMs}")

        _sessionState.update {
            SessionState(
                isHost = true,
                hostUid = ProfileManager.getCurrentUserUid(), // Set Host UID
                isHandshaking = true, // Start transition immediately
                currentMediaId = engine.mediaId,
                playbackStatus = SessionState.Status.PAUSED, // Start paused
                trackStartGlobalTime = now,
                positionAtAnchor = engine.currentPositionMs,
                playbackSpeed = engine.playbackSpeed,
                // UX: Host starts in WAITING status
                syncStatus = SessionState.SyncStatus.WAITING,
                clockSyncMessage = "Waiting for participants...",
                connectedPeerNames = getCurrentUserName().let { 
                    if (it != null && it != "Unknown") mapOf("local-user" to it) else emptyMap() 
                }
            )
        }
        Log.d(TAG, "startSession: SessionState set - ${_sessionState.value}")

        scope.launch { transportLayer.connect(null) }
        
        // Start heartbeat to keep connection alive
        startHeartbeat()
        
        // Start Drifting Monitoring Task (Participant only)
        startDriftMonitor()
    }

    private fun startDriftMonitor() {
        stopDriftMonitor() // Ensure only one monitor runs
        driftMonitorJob = scope.launch {
            Log.i(TAG, "startDriftMonitor: Starting drift monitor")
            while (isActive) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                if (!isHost && _sessionState.value.playbackStatus == SessionState.Status.PLAYING) {
                    val state = _sessionState.value
                    if (state.trackStartGlobalTime > 0) {
                        // Use unified calculator for expected position
                        val expectedPos = calculateParticipantPosition(
                            anchorPos = state.positionAtAnchor,
                            anchorTime = state.trackStartGlobalTime,
                            speed = state.playbackSpeed
                        )
                        val actualPos = playbackEngine.getCurrentPosition()
                        
                        val drift = kotlin.math.abs(actualPos - expectedPos)
                        if (drift > DRIFT_THRESHOLD_MS) {
                            Log.w(TAG, "DRIFT MONITOR: Significant drift detected! actual=$actualPos, expected=$expectedPos, drift=${drift}ms. Resyncing...")
                            applyAuthoritativeSnapshot(state, "DriftMonitor")
                        } else {
                            Log.v(TAG, "DRIFT MONITOR: Drift within limits (${drift}ms)")
                        }
                    }
                }
            }
        }
    }

    fun joinSession(code: String) {
        android.util.Log.d("ytSync", "SessionManager: Friend is joining session with code=$code")
        Log.d(TAG, "joinSession: Start joining process for code: $code")
        Log.i(TAG, "joinSession: Joining as PARTICIPANT with code=$code")
        isHost = false
        // Also set the StateFlow to keep in sync
        _sessionState.update {
            it.copy(
                isHost = false,
                isHandshaking = true,
                clockSyncMessage = "Connecting to $code...",
                connectedPeerNames = getCurrentUserName().let { 
                    if (it != null && it != "Unknown") mapOf("local-user" to it) else emptyMap() 
                }
            )
        }
        scope.launch { transportLayer.connect(code) }
        
        // Start heartbeat to keep connection alive
        startHeartbeat()
        
        // RAPID SYNC: Quickly calibrate clock for Perfect Initial Sync
        startRapidSync()
        
        // Start Drift Monitor (Critical for Guests)
        startDriftMonitor()
    }

    /**
     * Stops the current session and disconnects from transport.
     */
    fun stopSession() {
        Log.i(TAG, "stopSession: Stopping session, isHost=$isHost")
        stopHeartbeat()  // Stop heartbeat before disconnecting
        stopDriftMonitor() // Stop drift monitor
        scope.launch { transportLayer.disconnect() }
        _sessionState.update { SessionState() } // Reset to default
        isHost = false
    }

    /**
     * Helper for Host to toggle Host-Only Mode.
     * When ON, participants cannot control playback.
     */
    fun setHostOnlyMode(enabled: Boolean) {
        if (!isHost) {
            Log.w(TAG, "setHostOnlyMode: Only Host can set Host-Only Mode")
            return
        }
        Log.i(TAG, "setHostOnlyMode: Setting to $enabled")
        
        // Update local state
        _sessionState.update { it.copy(hostOnlyMode = enabled) }
        
        // Broadcast new state to all participants
        val now = timeSyncEngine.getGlobalTime()
        val (title, artist, thumbnailUrl) = getCurrentMetadata()
        
        // Ensure metadata is populated in the state update
        val updatedState = _sessionState.value.copy(
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl
        )
        
        // Broadcast StateSyncEvent
        Log.i(TAG, "setHostOnlyMode: Broadcasting StateSyncEvent")
        eventBroadcaster?.invoke(StateSyncEvent(updatedState, now))
    }

    // ============================================================
    // PLAYBACK CONTROL METHODS (Called by PlayerService)
    // ============================================================

    /**
     * Resume playback. Broadcasts PlayEvent to everyone.
     * Uses scheduled start time for better sync accuracy.
     */
    fun resume() {
        // FIX 6: Block during snapshot application
        if (isApplyingSnapshot) {
            return
        }
        
        // Debounce rapid button presses
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastResumeTime < DEBOUNCE_MS) {
            Log.d(TAG, "resume: Debounced (too fast)")
            return
        }
        lastResumeTime = currentTime
        
        // Enforce Host-Only Mode
        if (!isHost && _sessionState.value.hostOnlyMode) {
            Log.d(TAG, "resume: Ignored (Host-Only Mode active)")
            return
        }
        
        Log.d(TAG, "resume: Broadcasting PlayEvent (Request if Participant)")
        
        // Broadcast intent to Host (Request Action) or to everyone if Host (Authoritative)
        val state = playbackEngine.playbackState.value
        val now = timeSyncEngine.getGlobalTime()
        
        val event = PlayEvent(
            mediaId = state.mediaId ?: return,
            startPos = state.currentPositionMs,
            timestamp = now,  // Current time (not future) - participants sync to host's current position
            playbackSpeed = state.playbackSpeed,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
        )
        Log.i(TAG, "resume: Sending PlayEvent at globalTime=$now")
        eventBroadcaster?.invoke(event)
        
        // Host plays IMMEDIATELY - no waiting
        if (isHost) {
            playbackEngine.play()
            
            // Update session state
            _sessionState.update { it.copy(
                playbackStatus = SessionState.Status.PLAYING,
                trackStartGlobalTime = now,
                positionAtAnchor = state.currentPositionMs
            ) }
        }
    }

    /**
     * Pause playback. Broadcasts PauseEvent to everyone.
     */
    fun pause() {
        // FIX 6: Block during snapshot application
        if (isApplyingSnapshot) {
            Log.d(TAG, "pause: BLOCKED (snapshot lock active)")
            return
        }
        
        // Debounce rapid button presses
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPauseTime < DEBOUNCE_MS) {
            Log.d(TAG, "pause: Debounced (too fast)")
            return
        }
        lastPauseTime = currentTime
        
        // Enforce Host-Only Mode
        if (!isHost && _sessionState.value.hostOnlyMode) {
            Log.d(TAG, "pause: Ignored (Host-Only Mode active)")
            return
        }
        
        Log.d(TAG, "pause: Broadcasting PauseEvent")
        
        // ALWAYS pause locally, regardless of host status
        playbackEngine.pause()
        
        val state = playbackEngine.playbackState.value
        val now = timeSyncEngine.getGlobalTime()
        val event = PauseEvent(
            pos = state.currentPositionMs,
            timestamp = now,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
        )
        Log.i(TAG, "pause: Sending PauseEvent at globalTime=$now")
        eventBroadcaster?.invoke(event)
        
        if (isHost) {
            _sessionState.update { it.copy(
                playbackStatus = SessionState.Status.PAUSED,
                positionAtAnchor = state.currentPositionMs
            ) }
        }
    }

    /**
     * Seek to position. Broadcasts SeekEvent to everyone.
     * Also auto-resumes playback for better UX.
     */
    fun seekTo(positionMs: Long) {
        // FIX 6: Block during snapshot application
        if (isApplyingSnapshot) {
            return
        }
        
        // Prevent re-entrancy (ExoPlayer callback triggering another seekTo)
        if (isSeeking) {
            Log.d(TAG, "seekTo: Skipping re-entrant call for pos=$positionMs")
            return
        }
        
        // Debounce rapid seeks (slider dragging)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSeekTime < DEBOUNCE_MS) {
            return
        }
        lastSeekTime = currentTime
        
        // Enforce Host-Only Mode
        if (!isHost && _sessionState.value.hostOnlyMode) {
            Log.d(TAG, "seekTo: Ignored (Host-Only Mode active)")
            return
        }
        
        Log.d(TAG, "seekTo: pos=$positionMs")
        isSeeking = true
        try {
            // PARTICIPANT: Only broadcast request, do NOT seek locally yet (Strict Authority)
            // HOST: Seek locally, then broadcast authoritative state
            
            if (isHost) {
                playbackEngine.seekTo(positionMs)

                // Auto-resume playback after seek for better UX
                val wasPlaying = _sessionState.value.playbackStatus == SessionState.Status.PLAYING
                if (!wasPlaying) {
                    Log.i(TAG, "seekTo: Auto-resuming playback after seek")
                    playbackEngine.play()
                }
            }
            
            val now = timeSyncEngine.getGlobalTime()
            val event = SeekEvent(
                pos = positionMs,
                timestamp = now,
                requesterName = getCurrentUserName()  // Always include name (Host or Participant)
            )
            Log.i(TAG, "seekTo: Sending SeekEvent at globalTime=$now - pos=${event.pos}")
            eventBroadcaster?.invoke(event)
            
            if (isHost) {
                _sessionState.update { it.copy(
                    positionAtAnchor = positionMs,
                    trackStartGlobalTime = now,
                    playbackStatus = SessionState.Status.PLAYING  // Update status since we auto-resumed
                ) }
            }
            
            // Also broadcast PlayEvent if we auto-resumed (for Participant intent or Host update)
            // If participant, we request Play (resume) after seek.
            val wasPlaying = _sessionState.value.playbackStatus == SessionState.Status.PLAYING
            if (!wasPlaying) {
                val playEvent = PlayEvent(
                    mediaId = _sessionState.value.currentMediaId ?: "",
                    startPos = positionMs,
                    timestamp = now,
                    requesterName = getCurrentUserName()  // Always include name
                )
                Log.i(TAG, "seekTo: Broadcasting PlayEvent for auto-resume")
                eventBroadcaster?.invoke(playEvent)
            }
        } finally {
            isSeeking = false
        }
    }

    // ============================================================
    // SOCIAL INTERACTION COMMANDS
    // ============================================================

    fun sendReaction(emoji: String) {
        val localNow = System.currentTimeMillis()
        if (localNow - lastReactionTime < 150) {
            Log.d(TAG, "sendReaction: Rate limited (spam protection)")
            return
        }
        lastReactionTime = localNow

        val now = timeSyncEngine.getGlobalTime()
        val event = ReactionEvent(
            emoji = emoji,
            timestamp = now,
            senderName = getCurrentUserName()
        )
        processedSocialEvents.add(event.eventId)
        Log.i(TAG, "sendReaction: $emoji")
        eventBroadcaster?.invoke(event)
        // Also emit locally
        scope.launch { _socialEvents.emit(event) }
    }

    fun sendFlashMessage(message: String) {
        val localNow = System.currentTimeMillis()
        if (localNow - lastFlashMessageTime < 500) { // Slightly longer cooldown for large text
            Log.d(TAG, "sendFlashMessage: Rate limited (spam protection)")
            return
        }
        lastFlashMessageTime = localNow

        val now = timeSyncEngine.getGlobalTime()
        val event = FlashMessageEvent(
            message = message,
            timestamp = now,
            senderName = getCurrentUserName()
        )
        processedSocialEvents.add(event.eventId)
        Log.i(TAG, "sendFlashMessage: $message")
        eventBroadcaster?.invoke(event)
        // Also emit locally
        scope.launch { _socialEvents.emit(event) }
    }

    fun sendKineticTouch(x: Float, y: Float) {
        val localNow = System.currentTimeMillis()
        if (localNow - lastKineticTouchTime < 100) { // Fast cooldown for fluid ripples
            Log.d(TAG, "sendKineticTouch: Rate limited (spam protection)")
            return
        }
        lastKineticTouchTime = localNow

        val now = timeSyncEngine.getGlobalTime()
        val event = KineticTouchEvent(
            x = x,
            y = y,
            timestamp = now
        )
        processedSocialEvents.add(event.eventId)
        Log.d(TAG, "sendKineticTouch: x=$x, y=$y")
        eventBroadcaster?.invoke(event)
        // Also emit locally
        scope.launch { _socialEvents.emit(event) }
    }

    /**
     * Called when track changes. Broadcasts PlayEvent.
     * 
     * STRICT RULE: Only broadcast if NOT triggered by applyAuthoritativeSnapshot.
     * This prevents broadcast storms where participant echoes back events from sync.
     * User-initiated track changes (e.g., clicking song in browse) SHOULD broadcast.
     * 
     * @param reason The reason for the transition (Player.MEDIA_ITEM_TRANSITION_REASON_*)
     */
    fun onTrackChanged(mediaId: String, reason: Int) {
        Log.d(TAG, "onTrackChanged: mediaId=$mediaId, reason=$reason, isHost=$isHost, isApplyingSnapshot=$isApplyingSnapshot")
        
        // If triggered by applyAuthoritativeSnapshot, don't broadcast (prevents echo loop)
        if (isApplyingSnapshot) {
            Log.d(TAG, "onTrackChanged: Suppressing broadcast (triggered by snapshot apply)")
            return
        }
        
        // CRITICAL FIX: Check if this track change is an echo from a recent sync event
        // loadTrack is async, so isApplyingSnapshot may be false by the time this callback fires
        if (mediaId == lastSyncedMediaId && (System.currentTimeMillis() - lastSyncedTimestamp) < SYNC_ECHO_SUPPRESS_MS) {
            Log.d(TAG, "onTrackChanged: Suppressing echo broadcast (track was synced ${System.currentTimeMillis() - lastSyncedTimestamp}ms ago)")
            lastSyncedMediaId = null  // Clear to allow future user-initiated changes
            return
        }
        
        // Check Host-Only Mode for participants
        if (!isHost && _sessionState.value.hostOnlyMode) {
            Log.d(TAG, "onTrackChanged: Participant blocked (Host-Only Mode). Triggering snap-back resync.")
            requestFullSync("manual-change-blocked")
            return
        }
        
        // Get metadata for the track
        val metadata = metadataProvider?.invoke()
        Log.d("MusicSyncFlow", "onTrackChanged: [HOST] Retrieved metadata for $mediaId: Title='${metadata?.first}', Artist='${metadata?.second}', Art='${metadata?.third}'")
        
        // Broadcast the track change (Host or Participant requesting new track)
        val now = timeSyncEngine.getGlobalTime()
        val scheduledStartTime = if (isHost) now + SYNC_LEAD_TIME_MS else now
        
        // FIX: Generate fingerprint INLINE for local media, because the playbackState
        // onEach collector may not have run yet (race condition).
        var fingerprint = _sessionState.value.mediaFingerprint
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://")) {
            val freshFingerprint = com.github.musicyou.utils.DeviceMediaManager.getFingerprintFromUri(context, mediaId)
            if (freshFingerprint != null) {
                fingerprint = freshFingerprint
                Log.i("MusicSyncFlow", "onTrackChanged: Generated fingerprint inline: ${freshFingerprint.title} (${freshFingerprint.durationMs}ms)")
                _sessionState.update { it.copy(mediaFingerprint = freshFingerprint) }
            } else {
                Log.e("MusicSyncFlow", "onTrackChanged: Failed to generate fingerprint for $mediaId")
            }
        }
        
        val event = PlayEvent(
            mediaId = mediaId,
            startPos = 0L,
            timestamp = scheduledStartTime,
            playbackSpeed = playbackEngine.playbackState.value.playbackSpeed,
            title = metadata?.first,
            artist = metadata?.second,
            thumbnailUrl = metadata?.third,
            requesterName = getCurrentUserName(),  // Always include name (Host or Participant)
            mediaFingerprint = fingerprint  // Include fingerprint for local media matching
        )
        
        if (isHost) {
            Log.i(TAG, "onTrackChanged: HOST performing Synchronous Start (delay=${SYNC_LEAD_TIME_MS}ms) for $mediaId")
            
            // 1. Pause temporarily so we don't get ahead while waiting for precision start
            // Set flag to suppress the PauseEvent broadcast from the auto-detector
            isApplyingSnapshot = true 
            playbackEngine.pause()
            // Reset flag after short delay (enough for callback to assume it's handled)
            scope.launch { 
                delay(100)
                isApplyingSnapshot = false 
            }
            
            // 2. Broadcast the Future PlayEvent
            _sessionState.update { current ->
                current.copy(
                    currentMediaId = mediaId,
                    playbackStatus = SessionState.Status.PLAYING,
                    trackStartGlobalTime = scheduledStartTime,
                    positionAtAnchor = 0L,
                    title = metadata?.first,
                    artist = metadata?.second,
                    thumbnailUrl = metadata?.third
                )
            }
            android.util.Log.d("ytSync", "SessionManager: HOST sending PlayEvent for mediaId=$mediaId at globalTime=$scheduledStartTime")
            Log.i(TAG, "onTrackChanged: HOST sending PlayEvent at globalTime=$scheduledStartTime for $mediaId")
            eventBroadcaster?.invoke(event)
            
            // 3. Wait and Play
            scope.launch {
                val waitTime = scheduledStartTime - timeSyncEngine.getGlobalTime()
                if (waitTime > 0) {
                     delay(waitTime)
                }
                playbackEngine.play()
            }
        } else {
            Log.i(TAG, "onTrackChanged: PARTICIPANT requesting track change - mediaId=$mediaId at globalTime=$now")
            eventBroadcaster?.invoke(event)
        }
    }

    // ============================================================
    // EVENT HANDLING
    // ============================================================

    fun processEvent(event: SyncEvent, senderId: String? = null) {
        val now = timeSyncEngine.getGlobalTime()
        val latency = now - event.timestamp
        val names = _sessionState.value.connectedPeerNames.values.joinToString(", ")
        
        if (event !is PingEvent && event !is PongEvent) {
            Log.i(TAG, "processEvent: [${event::class.simpleName}] | SenderId: $senderId | Latency: ${latency}ms | Peers: [${_sessionState.value.connectedPeers.size}] | Names: [$names]")
        } else {
            Log.v(TAG, "processEvent: [${event::class.simpleName}] | Peers: [${_sessionState.value.connectedPeers.size}]")
        }
        
        // Host should process all events (Control requests from participants + RequestState/Ping/Pong)
        // EXCEPT if Host-Only Mode is active - then ignore request events from participants
        if (isHost && _sessionState.value.hostOnlyMode && 
            event !is RequestStateEvent && event !is PingEvent && event !is JoinEvent && event !is PongEvent &&
            event !is ReactionEvent && event !is FlashMessageEvent && event !is KineticTouchEvent) {
            Log.d(TAG, "processEvent: Host ignoring participant request event due to Host-Only Mode: ${event::class.simpleName}")
            broadcastAuthoritativeState()
            return
        }


        when (event) {
            is RequestStateEvent -> {
                Log.i(TAG, "processEvent: RequestStateEvent received from ${event.senderName}, avatar=${event.senderAvatar} (senderId=$senderId)")
                if (!isHost) {
                    Log.d(TAG, "processEvent: Participant ignoring RequestStateEvent")
                    return
                }
                
                // Track sender's name and avatar if provided
                event.senderName?.let { name ->
                    _sessionState.update { current ->
                        val updatedNames = current.connectedPeerNames.toMutableMap()
                        val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                        val updatedUids = current.connectedPeerUids.toMutableMap()
                        // Map the name to the stable senderId if available
                        val key = senderId ?: name
                        updatedNames[key] = name
                        updatedAvatars[key] = event.senderAvatar
                        updatedUids[key] = event.senderUid
                        
                        // Also ensure local name/avatar is there
                        getCurrentUserName()?.let { myName ->
                            if (myName != "Unknown") updatedNames["local-user"] = myName
                        }
                        getCurrentAvatar()?.let { myAvatar ->
                            updatedAvatars["local-user"] = myAvatar
                        }
                        ProfileManager.getCurrentUserUid()?.let { myUid ->
                            updatedUids["local-user"] = myUid
                        }
                        
                        current.copy(
                            connectedPeerNames = updatedNames,
                            connectedPeerAvatars = updatedAvatars,
                            connectedPeerUids = updatedUids
                        )
                    }
                    val names = _sessionState.value.connectedPeerNames.values.joinToString(", ")
                    Log.i(TAG, "processEvent: Participant list updated (RequestState): [$names]")
                    
                    // BROADCAST updated list to ALL participants
                    broadcastAuthoritativeState()
                }

                val state = _sessionState.value
                val now = timeSyncEngine.getGlobalTime()
                Log.i(TAG, "processEvent: HOST responding with StateSyncEvent - mediaId=${state.currentMediaId}, status=${state.playbackStatus}")
                eventBroadcaster?.invoke(StateSyncEvent(state, now))
            }
            
            is JoinEvent -> {
                Log.i(TAG, "processEvent: JoinEvent received - name=${event.name}, avatar=${event.avatar} (senderId=$senderId)")
                _sessionState.update { current ->
                    val updatedNames = current.connectedPeerNames.toMutableMap()
                    val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                    val updatedUids = current.connectedPeerUids.toMutableMap()
                    
                    // Map the name to the stable senderId if available
                    val key = senderId ?: event.name
                    updatedNames[key] = event.name
                    updatedAvatars[key] = event.avatar
                    updatedUids[key] = event.uid
                    
                    // Also ensure local name/avatar is there
                    getCurrentUserName()?.let { myName ->
                        if (myName != "Unknown") updatedNames["local-user"] = myName
                    }
                    getCurrentAvatar()?.let { myAvatar ->
                        updatedAvatars["local-user"] = myAvatar
                    }
                    ProfileManager.getCurrentUserUid()?.let { myUid ->
                        updatedUids["local-user"] = myUid
                    }
                    
                    current.copy(
                        connectedPeerNames = updatedNames,
                        connectedPeerAvatars = updatedAvatars,
                        connectedPeerUids = updatedUids
                    )
                }
                val names = _sessionState.value.connectedPeerNames.values.joinToString(", ")
                Log.i(TAG, "processEvent: Participant list updated (JoinEvent): [$names]")
                
                // Show toast that someone joined
                toastHandler?.invoke("${event.name} joined the session")
                
                // BROADCAST updated list to all participants
                if (isHost) {
                    broadcastAuthoritativeState()
                }
            }

            is StateSyncEvent -> {
                Log.i(TAG, "processEvent: StateSyncEvent received - mediaId=${event.state.currentMediaId}, status=${event.state.playbackStatus}, hostUid=${event.state.hostUid}")
                // Authoritative snapshot override
                // Merge peer lists on participant to catch up on existing members
                _sessionState.update { current ->
                    val updatedNames = current.connectedPeerNames.toMutableMap()
                    val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                    val updatedUids = current.connectedPeerUids.toMutableMap()
                    updatedNames.putAll(event.state.connectedPeerNames)
                    updatedAvatars.putAll(event.state.connectedPeerAvatars)
                    updatedUids.putAll(event.state.connectedPeerUids)
                    current.copy(
                        connectedPeerNames = updatedNames,
                        connectedPeerAvatars = updatedAvatars,
                        connectedPeerUids = updatedUids
                    )
                }
                applyAuthoritativeSnapshot(event.state, "StateSyncEvent")
            }
            
            is PlayEvent -> {
                Log.i(TAG, "processEvent: PlayEvent received - mediaId=${event.mediaId}, title=${event.title}, startPos=${event.startPos}, requester=${event.requesterName}")
                
                // Use HOST's current time as anchor for fresh sync (not stale participant timestamp)
                val hostNow = timeSyncEngine.getGlobalTime()
                val hostPos = if (isHost) playbackEngine.playbackState.value.currentPositionMs else event.startPos
                
                applyAuthoritativeSnapshot(
                    SessionState(
                        currentMediaId = event.mediaId,
                        playbackStatus = SessionState.Status.PLAYING,
                        trackStartGlobalTime = hostNow,  // Use HOST's current time
                        positionAtAnchor = hostPos,  // Use HOST's current position
                        playbackSpeed = event.playbackSpeed,
                        title = event.title,
                        artist = event.artist,
                        thumbnailUrl = event.thumbnailUrl,
                        mediaFingerprint = event.mediaFingerprint // Propagate fingerprint to state
                    ),
                    "PlayEvent"
                )
                // Aggressive Name Collection (capture name/avatar from request)
                // This ensures we have display info even before formal handshake completes
                event.requesterName?.let { name ->
                    _sessionState.update { current ->
                        val updatedNames = current.connectedPeerNames.toMutableMap()
                        val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                        
                        val key = senderId ?: name
                        // Only update if missing or placeholder
                        if (!updatedNames.containsKey(key) || updatedNames[key] == "Connecting...") {
                            Log.i(TAG, "Aggressive Collection: Registering $name for key $key")
                            updatedNames[key] = name
                            updatedAvatars[key] = event.requesterAvatar
                        }
                        current.copy(
                            connectedPeerNames = updatedNames,
                            connectedPeerAvatars = updatedAvatars
                        )
                    }
                }

                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name played: ${event.title ?: "track"}")
                    }
                }
                // FIX 5: Host uses COALESCED broadcast instead of immediate dual broadcasts
                if (isHost) {
                    Log.i(TAG, "processEvent: HOST scheduling coalesced broadcast for PlayEvent from requester=${event.requesterName}")
                    broadcastAuthoritativeState()
                }
            }

            is PauseEvent -> {
                Log.i(TAG, "processEvent: PauseEvent received - pos=${event.pos}, requester=${event.requesterName}")
                
                // Create snapshot from event (not current state)
                val pauseSnapshot = _sessionState.value.copy(
                    playbackStatus = SessionState.Status.PAUSED,
                    positionAtAnchor = event.pos,
                    trackStartGlobalTime = timeSyncEngine.getGlobalTime()
                )
                
                // Apply snapshot FIRST (ensures pause executes before state update)
                applyAuthoritativeSnapshot(pauseSnapshot, "PauseEvent")
                
                // THEN update state for UI consistency
                _sessionState.update { pauseSnapshot }
                
                // Aggressive Name Collection: Capture requester's name and avatar
                event.requesterName?.let { name ->
                    _sessionState.update { current ->
                        val updatedNames = current.connectedPeerNames.toMutableMap()
                        val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                        val key = senderId ?: name
                        if (!updatedNames.containsKey(key) || updatedNames[key] == "Connecting...") {
                            Log.i(TAG, "Aggressive Collection: Registering $name for key $key from PauseEvent")
                            updatedNames[key] = name
                            updatedAvatars[key] = event.requesterAvatar
                        }
                        current.copy(
                            connectedPeerNames = updatedNames,
                            connectedPeerAvatars = updatedAvatars
                        )
                    }
                }

                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name paused playback")
                    }
                }
                // FIX 5: Host uses COALESCED broadcast instead of immediate dual broadcasts
                if (isHost) {
                    Log.i(TAG, "processEvent: HOST scheduling coalesced broadcast for PauseEvent from requester=${event.requesterName}")
                    broadcastAuthoritativeState()
                }
            }

            is SeekEvent -> {
                Log.i(TAG, "processEvent: SeekEvent received - pos=${event.pos}, requester=${event.requesterName}")
                _sessionState.update { current ->
                    current.copy(
                        positionAtAnchor = event.pos,
                        trackStartGlobalTime = timeSyncEngine.getGlobalTime()
                    )
                }
                applyAuthoritativeSnapshot(_sessionState.value, "SeekEvent")
                // Aggressive Name Collection: Capture requester's name and avatar
                event.requesterName?.let { name ->
                    _sessionState.update { current ->
                        val updatedNames = current.connectedPeerNames.toMutableMap()
                        val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                        val key = senderId ?: name
                        if (!updatedNames.containsKey(key) || updatedNames[key] == "Connecting...") {
                            Log.i(TAG, "Aggressive Collection: Registering $name for key $key from SeekEvent")
                            updatedNames[key] = name
                            updatedAvatars[key] = event.requesterAvatar
                        }
                        current.copy(
                            connectedPeerNames = updatedNames,
                            connectedPeerAvatars = updatedAvatars
                        )
                    }
                }

                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name seeked to ${formatTime(event.pos)}")
                    }
                }
                // FIX 5: Host uses COALESCED broadcast instead of immediate dual broadcasts
                if (isHost) {
                    Log.i(TAG, "processEvent: HOST scheduling coalesced broadcast for SeekEvent from requester=${event.requesterName}")
                    broadcastAuthoritativeState()
                }
            }

            is PingEvent -> {
                // NTP T1: Capture receive time immediately on Host's clock
                val receiveTime = System.currentTimeMillis() 
                
                Log.v(TAG, "processEvent: PingEvent received - id=${event.id}")
                
                // GUEST VALIDATION: Check for major desync (missed events)
                if (!isHost && event.currentMediaId != null) {
                    scope.launch {
                        val localMediaId = _sessionState.value.currentMediaId
                        val localStatus = _sessionState.value.playbackStatus
                        val localPos = playbackEngine.getCurrentPosition()
                        
                        // 1. Check Media Mismatch
                        if (localMediaId != event.currentMediaId) {
                            Log.w("MusicSyncFlow", "VALIDATION FAIL: Media Mismatch! Local=$localMediaId, Host=${event.currentMediaId}. Requesting Sync.")
                            requestFullSync("validation-fail-media")
                        } 
                        // 2. Check Status Mismatch (Host Playing, Local Paused)
                        else if (event.playbackStatus == SessionState.Status.PLAYING && localStatus != SessionState.Status.PLAYING) {
                             Log.w("MusicSyncFlow", "VALIDATION FAIL: Status Mismatch! Local=$localStatus, Host=PLAYING. Requesting Sync.")
                             requestFullSync("validation-fail-status")
                        }
                        // 3. Check Major Position Drift (> 3s)
                        else if (localStatus == SessionState.Status.PLAYING && event.playbackStatus == SessionState.Status.PLAYING) {
                             val diff = kotlin.math.abs(localPos - event.currentPos)
                             if (diff > 3000) {
                                  Log.w("MusicSyncFlow", "VALIDATION FAIL: Major Drift! Local=$localPos, Host=${event.currentPos}, Diff=$diff. Requesting Sync.")
                                  requestFullSync("validation-fail-drift")
                             }
                        }
                    }
                }
                
                // PHASE 2 FIX: BOTH host and participant must respond with pongs
                // This is critical for bidirectional heartbeat validation
                // NTP T2: Capture reply time (processing done)
                val replyTime = System.currentTimeMillis() 
                
                val pong = PongEvent(
                    id = event.id,
                    clientTimestamp = event.clientTimestamp,
                    serverTimestamp = receiveTime, // T1: When we received PING
                    serverReplyTimestamp = replyTime, // T2: When we send PONG
                    timestamp = replyTime // Redundant helper
                )
                val role = if (isHost) "HOST" else "PARTICIPANT"
                // Log.v(TAG, "processEvent: $role sending PongEvent for ping ${event.id}")
                eventBroadcaster?.invoke(pong)
            }

            is PongEvent -> {
                Log.d(TAG, "processEvent: PongEvent received - id=${event.id}")
                
                // PHASE 2: Complete heartbeat promise (CRITICAL - activates 3-strike disconnect)
                // This must happen for ALL pongs (host and participant)
                pendingPongs[event.id]?.complete(true)
                
                // Participant processes pong for time synchronization
                if (!isHost) {
                    val t3 = System.currentTimeMillis()
                    timeSyncEngine.processPong(
                        t0 = event.clientTimestamp,
                        t1 = event.serverTimestamp,
                        t2 = event.serverReplyTimestamp,
                        t3 = t3
                    )
                } else {
                    // HOST: Calculate RTT for UI display only (do NOT update time offset)
                    // RTT = (t3 - t0) - (ServerProcessingTime)
                    val t3 = System.currentTimeMillis()
                    val serverProcessing = event.serverReplyTimestamp - event.serverTimestamp
                    val rtt = (t3 - event.clientTimestamp) - serverProcessing
                    
                    if (rtt in 0..5000) { // Sanity check
                        _sessionState.update { it.copy(ping = rtt) }
                    }
                }
            }

            is ReactionEvent, is FlashMessageEvent, is KineticTouchEvent -> {
                val eventId = when (event) {
                    is ReactionEvent -> event.eventId
                    is FlashMessageEvent -> event.eventId
                    is KineticTouchEvent -> event.eventId
                    else -> return
                }
                
                if (!processedSocialEvents.add(eventId)) {
                    Log.d(TAG, "processEvent: Ignoring duplicate/echoed social event $eventId")
                    return
                }

                // Emit to social flow for UI to handle
                scope.launch { _socialEvents.emit(event) }
                
                // HOST RELAY
                if (isHost) {
                    Log.i(TAG, "processEvent: HOST relaying social event to all peers")
                    eventBroadcaster?.invoke(event)
                }
            }
        }
    }

    // ============================================================
    // AUTHORITATIVE APPLY (CORE FIX)
    // ============================================================

    private fun applyAuthoritativeSnapshot(state: SessionState, source: String) {
        // PHANTOM STATE PROTECTION:
        // Detect if we are receiving an empty/default state (v0, null IDs) while we have a valid session.
        // This prevents accidental wipes of metadata.
        val isPhantom = !isHost && 
                        state.currentMediaId == null && 
                        state.hostUid == null && 
                        state.stateVersion == 0L
        
        val hasValidSession = _sessionState.value.currentMediaId != null
        
        if (isPhantom && hasValidSession) {
            Log.w(TAG, "applyAuthoritativeSnapshot: BLOCKED PHANTOM STATE from $source! (Inc=null/v0 vs Loc=${_sessionState.value.currentMediaId})")
            return
        }

        // CRITICAL FIX: Wrap entire apply in Mutex to prevent concurrent modification
        // This guarantees that version checks and state updates are atomic
        scope.launch {
            applyLock.withLock {
                try {
                    Log.i(TAG, "applyAuthoritativeSnapshot: Acquired lock - applying state v${state.stateVersion} (hostUid=${state.hostUid}) from source=$source")
                    
                    // VERSION CHECK INSIDE LOCK - Prevents TOCTOU race where newer snapshot arrives during apply
                    // FIX: Allow DriftMonitor to bypass version check (force resync even if version matches)
                    val isDriftCorrection = source == "DriftMonitor"
                    if (!isHost && !isDriftCorrection && state.stateVersion > 0 && state.stateVersion <= lastAppliedVersion) {
                        Log.d(TAG, "applyAuthoritativeSnapshot: IGNORED (stale version ${state.stateVersion} <= $lastAppliedVersion)")
                        return@withLock
                    }
                    
                    // Check if another apply is in progress for a newer version
                    currentApplyingVersion?.let { applying ->
                        if (state.stateVersion <= applying) {
                            Log.d(TAG, "applyAuthoritativeSnapshot: IGNORED (already applying newer version $applying)")
                            return@withLock
                        }
                    }
                    
                    // EVENT DEDUPLICATION - Smart Check
                    // Use unified calculator to determine where we SHOULD be based on the state's anchor + elapsed time
                    // This creates a valid comparison for both new events (elapsed~0) and drift corrections (elapsed>0)
                    
                    val now = timeSyncEngine.getGlobalTime()
                    
                    // FIX: If PAUSED, effective speed is 0 so we don't project time forward
                    val effectiveSpeed = if (state.playbackStatus == SessionState.Status.PAUSED) 0f else state.playbackSpeed
                    
                    val expectedPos = calculateParticipantPosition(
                        anchorPos = state.positionAtAnchor,
                        anchorTime = state.trackStartGlobalTime,
                        speed = effectiveSpeed
                    )
                    // FIX: Use real-time position
                    val currentPos = playbackEngine.getCurrentPosition()
                    val positionDrift = kotlin.math.abs(expectedPos - currentPos)
                    
                    Log.d("MusicSyncFlow", "applySnapshot: Drift Analysis - Current=$currentPos, Expected=$expectedPos, Drift=$positionDrift, Speed=$effectiveSpeed, Status=${state.playbackStatus}")
                    
                    val currentMediaId = playbackEngine.playbackState.value.mediaId
                    val currentIsPlaying = playbackEngine.playbackState.value.isPlaying
                    val currentStatus = if (currentIsPlaying) SessionState.Status.PLAYING else SessionState.Status.PAUSED
                    
                    // Strict dedup: If media/status/speed match AND we are within drift threshold of the TARGET position, ignore.
                    // Strict dedup: If media/status match AND we are within drift threshold of the TARGET position, ignore.
                    // FIX: Bypass dedup for DriftMonitor to ensure correction happens
                    // FIX: Also check playback speed to prevent ignoring speed changes
                    // FIX: Always apply status changes (play/pause) - never dedup them
                    val currentSpeed = playbackEngine.playbackState.value.playbackSpeed
                    val isStatusChange = state.playbackStatus != currentStatus  // Detect play/pause changes
                    if (!isDriftCorrection &&
                        !isStatusChange &&  // Never dedup status changes (play/pause)
                        state.currentMediaId == lastAppliedMediaId &&
                        state.playbackStatus == lastAppliedStatus &&
                        state.currentMediaId == currentMediaId &&
                        state.playbackStatus == currentStatus &&
                        state.playbackSpeed == currentSpeed &&  // Check speed to detect speed changes
                        positionDrift < DRIFT_THRESHOLD_MS && 
                        (System.currentTimeMillis() - lastAppliedTimestamp) < DEDUP_THRESHOLD_MS) {
                        Log.d("MusicSyncFlow", "applySnapshot: IGNORED (dedup/drift limits)")
                        return@withLock
                    }
                    
                    // Mark this version as being applied
                    currentApplyingVersion = state.stateVersion
                    
                    // Update deduplication tracking
                    lastAppliedMediaId = state.currentMediaId
                    lastAppliedStatus = state.playbackStatus
                    lastAppliedPosition = state.positionAtAnchor
                    lastAppliedTimestamp = System.currentTimeMillis()
                    if (state.stateVersion > 0) {
                        lastAppliedVersion = state.stateVersion
                    }
                    
                    isApplyingSnapshot = true
        
            // APPLY STATE
            _sessionState.update { current ->
                Log.d("MusicSyncFlow", "applySnapshot: [GUEST] Applying state. MediaId(Inc: ${state.currentMediaId} vs Loc: ${current.currentMediaId}). Incoming Metadata: Title='${state.title}'")
                
                // MERGE FIX: Start with current data to prevent loss during partial updates (Play/Pause)
                // These events result in empty/partial maps in 'state', so we must preserve 'current' data
                val mergedNames = current.connectedPeerNames.toMutableMap()
                val mergedAvatars = current.connectedPeerAvatars.toMutableMap()
                val mergedUids = current.connectedPeerUids.toMutableMap()
                
                // Apply incoming updates (overwrites existing keys if present)
                mergedNames.putAll(state.connectedPeerNames)
                mergedAvatars.putAll(state.connectedPeerAvatars)
                mergedUids.putAll(state.connectedPeerUids)

                // Ensure "Me" is present
                getCurrentUserName()?.let { myName ->
                    if (myName != "Unknown" && !mergedNames.containsKey(myName)) {
                        mergedNames[myName] = myName
                    }
                }

                // METADATA FIX: Use existing metadata if incoming is null but mediaId matches
                // This prevents "flickering" or loss of info if Host sends partial update
                val useExistingMetadata = state.currentMediaId == current.currentMediaId &&
                                         state.title == null && 
                                         current.title != null
                                         
                val finalTitle = if (useExistingMetadata) {
                     Log.d("MusicSyncFlow", "applySnapshot: Retaining local metadata (Incoming is null)")
                     current.title
                } else state.title
                
                val finalArtist = if (useExistingMetadata) current.artist else state.artist
                val finalUrl = if (useExistingMetadata) current.thumbnailUrl else state.thumbnailUrl

                state.copy(
                    isHost = current.isHost,
                    sessionId = current.sessionId,
                    // FIX: Preserve hostUid if already known (e.g. from RTDB), don't overwrite with null from partial update
                    hostUid = if (current.isHost) current.hostUid else (state.hostUid ?: current.hostUid),
                    connectedPeers = current.connectedPeers, // Keep IDs from current (synced via Presence)
                    
                    // Use MERGED maps for participants to prevent data loss
                    connectedPeerNames = if (current.isHost) current.connectedPeerNames else mergedNames,
                    connectedPeerAvatars = if (current.isHost) current.connectedPeerAvatars else mergedAvatars,
                    connectedPeerUids = if (current.isHost) current.connectedPeerUids else mergedUids,
                    
                    title = finalTitle,
                    artist = finalArtist,
                    thumbnailUrl = finalUrl
                )
            }

        // Use unified calculator for target position
        val targetPos = calculateParticipantPosition(
            anchorPos = state.positionAtAnchor,
            anchorTime = state.trackStartGlobalTime,
            speed = effectiveSpeed
        )

        // Check if we need to load a new track or just update playback state
        // Use lastSyncedMediaId as well, because loadTrack is async and playbackEngine's mediaId lags
        // FIX: Check for Local File Match
        var resolvedUri: String? = null
        var syncMessage = state.clockSyncMessage
        
        // CRITICAL GUARD: Protect ALL clients (Host and Guest) from loading a peer's raw content:// URI.
        val isPeerLocalUri = state.currentMediaId?.startsWith("content://") == true || state.currentMediaId?.startsWith("file://") == true
        
        // Construct a MediaItem with metadata from the state so the Player UI doesn't crash/show white screen
        var mediaItem: androidx.media3.common.MediaItem? = null
        state.currentMediaId?.let { mediaId ->
            val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            state.title?.let { metadataBuilder.setTitle(it) }
            state.artist?.let { metadataBuilder.setArtist(it) }
            state.thumbnailUrl?.let { metadataBuilder.setArtworkUri(android.net.Uri.parse(it)) }
            
            val mediaItemBuilder = androidx.media3.common.MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadataBuilder.build())
            
            if (resolvedUri != null) {
                mediaItemBuilder.setUri(resolvedUri)
            }
            mediaItem = mediaItemBuilder.build()
        }

        // If the engine currently has no mediaItem (e.g. we rejoined the same session but engine state was lost/null), 
        // we must force a reload so the UI gets the metadata!
        val isMissingMetadata = state.currentMediaId != null && playbackEngine.playbackState.value.mediaItem == null
        val isSameTrack = !isMissingMetadata && (currentMediaId == state.currentMediaId || lastSyncedMediaId == state.currentMediaId) && state.currentMediaId != null

        if (!isSameTrack && isPeerLocalUri) {
            if (state.mediaFingerprint == null) {
                Log.w("MusicSyncFlow", "applySnapshot: ABORTING - received peer's local URI (${state.currentMediaId}) without fingerprint. Ignoring.")
                return@withLock
            }
            
            val match = com.github.musicyou.utils.DeviceMediaManager.findMatchingMedia(context, state.mediaFingerprint)
            if (match != null) {
                resolvedUri = match.toString()
                syncMessage = "Playing from Local Storage 📂"
                Log.d("MusicSyncFlow", "applySnapshot: Local match found: $resolvedUri")
                
                _sessionState.update { it.copy(
                    clockSyncMessage = syncMessage,
                    localMatchUri = resolvedUri
                )}
            } else {
                syncMessage = "File not found locally! ⚠️"
                Log.w(TAG, "applySnapshot: Local match failed for '${state.mediaFingerprint.title}'. Manual fallback prompted.")
                scope.launch {
                    toastHandler?.invoke("⚠️ Local Sync Failed\nMedia not found: '${state.mediaFingerprint.title}'")
                }
                
                // Fallback to manual selection
                _sessionState.update { current ->
                    current.copy(
                        playbackStatus = SessionState.Status.PAUSED,
                        clockSyncMessage = syncMessage,
                        isPendingManualMatch = true, // Trigger manual match UI
                        localMatchUri = null
                    )
                }
                Log.w("MusicSyncFlow", "applySnapshot: ABORTING - will not load peer's local URI on this device")
                playbackEngine.pause()
                return@withLock
            }
        } else if (isSameTrack && isPeerLocalUri) {
            // Keep using the existing resolved URI if we are already on this track
            resolvedUri = _sessionState.value.localMatchUri
        } else if (!isPeerLocalUri) {
            // Not a peer local URI, so clear any leftover local match
            _sessionState.update { it.copy(localMatchUri = null) }
        }
        
        if (state.mediaFingerprint == null && _sessionState.value.localMatchUri != null) {
             _sessionState.update { it.copy(localMatchUri = null) }
        }

        // Apply Playback Speed
        if (playbackEngine.playbackState.value.playbackSpeed != state.playbackSpeed) {
             Log.d("MusicSyncFlow", "applySnapshot: Changing speed to ${state.playbackSpeed}x")
             playbackEngine.setPlaybackSpeed(state.playbackSpeed)
        }

        if (isSameTrack) {
            // SAME TRACK - just update playback state without reloading
            val isYouTube = state.currentMediaId?.startsWith("youtube-embed:") == true
            val thresholdMs = if (isYouTube) 2000L else POSITION_DRIFT_THRESHOLD_MS
            val needsSeek = positionDrift > thresholdMs
            Log.d("MusicSyncFlow", "applySnapshot: Same Track - NeedsSeek=$needsSeek (Drift=$positionDrift > $thresholdMs)")
            
            when (state.playbackStatus) {
                SessionState.Status.PLAYING -> {
                    if (!isHost) {
                        if (needsSeek) {
                            val correctPos = calculateParticipantPosition(
                                anchorPos = state.positionAtAnchor,
                                anchorTime = state.trackStartGlobalTime,
                                speed = effectiveSpeed
                            )
                            val isYouTube = state.currentMediaId?.startsWith("youtube-embed:") == true
                            val thresholdMs = if (isYouTube) 2000L else POSITION_DRIFT_THRESHOLD_MS
                            Log.d("MusicSyncFlow", "applySnapshot: SEEKING guest to $correctPos (Reason: Drift $positionDrift > $thresholdMs)")
                            playbackEngine.seekTo(correctPos)
                        } else {
                            val isYouTube = state.currentMediaId?.startsWith("youtube-embed:") == true
                            val thresholdMs = if (isYouTube) 2000L else POSITION_DRIFT_THRESHOLD_MS
                            Log.d("MusicSyncFlow", "applySnapshot: Ignoring small drift (Drift $positionDrift < $thresholdMs). Keeping current pos.")
                        }
                        
                        if (!currentIsPlaying) {
                             Log.d("MusicSyncFlow", "applySnapshot: Starting playback (was paused)")
                             playbackEngine.play()
                        }
                        
                        _sessionState.update { it.copy(
                            syncStatus = SessionState.SyncStatus.READY,
                            clockSyncMessage = "Playing in sync! 🎵"
                        ) }
                    } else {
                        // HOST logic...
                        if (needsSeek) {
                            playbackEngine.seekTo(targetPos)
                        }
                        if (!currentIsPlaying) {
                            playbackEngine.play()
                        }
                    }
                }
                SessionState.Status.PAUSED -> {
                    Log.d("MusicSyncFlow", "applySnapshot: State PAUSED - Pausing engine")
                    playbackEngine.pause()
                    if (needsSeek) {
                        Log.d("MusicSyncFlow", "applySnapshot: SEEKING paused guest to $targetPos")
                        playbackEngine.seekTo(targetPos)
                    }
                }
                else -> {
                    playbackEngine.pause()
                }
            }
        } else {
            // DIFFERENT TRACK
            Log.d("MusicSyncFlow", "applySnapshot: New Track ${state.currentMediaId}")
            playbackEngine.pause()
            
            val shouldAutoPlay = state.playbackStatus == SessionState.Status.PLAYING
            val scheduledStartTime = state.trackStartGlobalTime
            // ... (rest of new track logic)
            state.currentMediaId?.let { mediaId ->
                lastSyncedMediaId = mediaId
                lastSyncedTimestamp = System.currentTimeMillis()
                
                val itemToLoad = mediaItem ?: return@let
                
                if (shouldAutoPlay && scheduledStartTime > now) {
                    Log.d("MusicSyncFlow", "applySnapshot: Scheduled Start for $mediaId")
                    if (resolvedUri != null || mediaId.startsWith("youtube-embed:")) {
                        playbackEngine.loadMediaItem(itemToLoad, state.positionAtAnchor, autoPlay = false)
                    } else {
                        playbackEngine.loadTrack(mediaId, state.positionAtAnchor, autoPlay = false, customUri = resolvedUri)
                    }
                    
                    scope.launch {
                        waitForScheduledTime(scheduledStartTime)
                        
                        val startPos = calculateParticipantPosition(
                            anchorPos = state.positionAtAnchor,
                            anchorTime = scheduledStartTime,
                            speed = state.playbackSpeed
                        )
                        
                        if (!isHost) {
                            Log.d("MusicSyncFlow", "applySnapshot: Scheduled Seek to $startPos")
                            playbackEngine.seekTo(startPos)
                        }
                        playbackEngine.play()
                    }
                } else {
                    Log.d("MusicSyncFlow", "applySnapshot: Immediate Load/Play for $mediaId")
                    if (resolvedUri != null || mediaId.startsWith("youtube-embed:")) {
                        playbackEngine.loadMediaItem(itemToLoad, targetPos, shouldAutoPlay)
                    } else {
                        playbackEngine.loadTrack(mediaId, targetPos, shouldAutoPlay, customUri = resolvedUri)
                    }
                }
            }
        }
                    
                    Log.i(TAG, "applyAuthoritativeSnapshot: DONE - mediaId=${state.currentMediaId}, status=${state.playbackStatus}")
                    
                } finally {
                    // GUARANTEED CLEANUP: Always clear the applying version token and flag
                    // Even if playback engine throws, network fails, or coroutine cancels
                    currentApplyingVersion = null
                    
                    // Delayed flag reset to cover async ExoPlayer callbacks (preserved original behavior)
                    scope.launch {
                        delay(SNAPSHOT_LOCK_DURATION_MS)
                        isApplyingSnapshot = false
                        Log.d(TAG, "applyAuthoritativeSnapshot: Echo suppression flag cleared after ${SNAPSHOT_LOCK_DURATION_MS}ms")
                    }
                    
                    Log.d(TAG, "applyAuthoritativeSnapshot: Lock released, version token cleared")
                }
            }
        }
    }
    
    /**
     * Format milliseconds to mm:ss for display.
     */
    /**
     * Manually forces playback of the locally matched file.
     * Used when automatic sync fails or user wants to override stream.
     */
    fun forcePlayLocal() {
        val uri = _sessionState.value.localMatchUri
        if (uri != null) {
            Log.i(TAG, "Force Play Local: Loading $uri")
            val currentPos = playbackEngine.playbackState.value.currentPositionMs
            val isPlaying = _sessionState.value.playbackStatus == SessionState.Status.PLAYING
            
            // Reload with custom URI
            playbackEngine.loadTrack(
                mediaId = _sessionState.value.currentMediaId ?: "unknown",
                seekPositionMs = currentPos,
                autoPlay = isPlaying,
                customUri = uri
            )
            
            // Update UI message
            _sessionState.update { it.copy(clockSyncMessage = "Forced Local Playback 📂") }
        } else {
            Log.w(TAG, "Force Play Local: No local match available to play")
        }
    }
    /**
     * Set a manual local file match when automatic matching fails.
     */
    fun setManualMatchUri(uri: String) {
        Log.i(TAG, "Manual file selection triggered! Overriding localMatchUri with: $uri")
        _sessionState.update { it.copy(
            localMatchUri = uri,
            clockSyncMessage = "Playing from manually selected file 📂",
            isPendingManualMatch = false // Clear pending flag once a file is selected
        )}
        forcePlayLocal()
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }
}


