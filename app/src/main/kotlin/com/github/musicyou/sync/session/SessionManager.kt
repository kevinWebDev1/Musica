package com.github.musicyou.sync.session

import android.util.Log
import com.github.musicyou.sync.playback.PlaybackEngine
import com.github.musicyou.sync.protocol.*
import com.github.musicyou.sync.time.TimeSyncEngine
import com.github.musicyou.sync.transport.TransportLayer
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.sync.presence.PresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val timeSyncEngine: TimeSyncEngine,
    val playbackEngine: PlaybackEngine,
    private val transportLayer: TransportLayer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "MusicSync"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L  // Send heartbeat every 5 seconds to keep connection alive
        private const val SYNC_ECHO_SUPPRESS_MS = 2000L 
        private const val SYNC_LEAD_TIME_MS = 0L       // 4s lead for snapshot scheduling
        private const val PARTICIPANT_LEAD_TIME_MS = 400L // 400ms participant lead vs host
        private const val DRIFT_THRESHOLD_MS = 800L      // 800ms drift threshold
        private const val DRIFT_CHECK_INTERVAL_MS = 5000L // 5s check interval
        private const val DEBOUNCE_MS = 300L
        
        // FIX 2: Extended snapshot lock duration to cover async ExoPlayer callbacks
        private const val SNAPSHOT_LOCK_DURATION_MS = 1500L
        
        // FIX 3: Event deduplication thresholds
        private const val DEDUP_THRESHOLD_MS = 500L
        private const val POSITION_DRIFT_THRESHOLD_MS = 500L
        
        // FIX 5: Host-side coalescing window
        private const val COALESCE_WINDOW_MS = 200L
        
        // PHASE 2: Heartbeat validation constants
        private const val MAX_MISSED_HEARTBEATS = 3  // 15s timeout (3 × 5s interval)
        private const val PONG_TIMEOUT_MS = 2000L    // 2s wait for each pong
    }

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

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
    
    // FIX 3: Event deduplication tracking
    private var lastAppliedMediaId: String? = null
    private var lastAppliedStatus: SessionState.Status? = null
    private var lastAppliedPosition: Long = 0L
    private var lastAppliedTimestamp: Long = 0L
    
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

    init {
        Log.d(TAG, "init: SessionManager created")
        
        // CRITICAL: Establish baseline presence (online/offline tracking)
        PresenceManager.connect()
        Log.i(TAG, "init: PresenceManager.connect() called - online presence established")
        
        // Sync transport layer's sessionId and connectedPeers to sessionState
        transportLayer.sessionId.onEach { sessionId ->
            Log.d(TAG, "init: sessionId changed to: $sessionId")
            _sessionState.update { current ->
                current.copy(
                    sessionId = sessionId,
                    isHandshaking = sessionId == null && current.isHandshaking // Clear if ID arrives
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
                
                // Add "Connecting..." placeholders for any NEW peer IDs that don't have names yet
                peers.forEach { peerId ->
                    if (!previousPeerNames.containsKey(peerId)) {
                        Log.d(TAG, "Instant Reactivity: Adding placeholder for $peerId")
                        previousPeerNames[peerId] = "Connecting..."
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
                }

                current.copy(
                    connectedPeers = peers.toSet(),
                    connectedPeerNames = previousPeerNames,
                    connectedPeerAvatars = previousPeerAvatars,
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
             if (isHost && transportPeers.size > previousTransportPeerCount && transportPeers.isNotEmpty()) {
                Log.i(TAG, "init: Transport detected new peer connection (${transportPeers.size} peers) - calling initiateSeamlessSync")
                
                // Trigger sync for the reconnected peer
                initiateSeamlessSync(
                    triggerSource = "Transport P2P Connect",
                    isFirstJoin = previousTransportPeerCount == 0
                )
            }
            previousTransportPeerCount = transportPeers.size
        }.launchIn(scope)
        
        // CORE FIX: Listen for playback state changes and broadcast when host is connected
        var lastIsPlaying: Boolean? = null
        var lastMediaId: String? = null
        var lastPosition: Long = 0L
        
        playbackEngine.playbackState.onEach { state ->
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
            
            if (isHost && (hasRtdbPeers || hasTransportPeers)) {
                val now = timeSyncEngine.getGlobalTime()
                
                // Detect play/pause change
                if (lastIsPlaying != null && lastIsPlaying != state.isPlaying) {
                    if (state.isPlaying) {
                        Log.i(TAG, "Auto-broadcast: Host started playing")
                        val (title, artist, thumbnailUrl) = getCurrentMetadata()
                        val event = PlayEvent(
                            mediaId = state.mediaId ?: "",
                            startPos = state.currentPositionMs,
                            timestamp = now,
                            title = title,
                            artist = artist,
                            thumbnailUrl = thumbnailUrl,
                            requesterName = getCurrentUserName(),
                            requesterAvatar = getCurrentAvatar()
                        )
                        eventBroadcaster?.invoke(event)
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
                        Log.i(TAG, "Auto-broadcast: Host paused")
                        val event = PauseEvent(
                            pos = state.currentPositionMs,
                            timestamp = now,
                            requesterName = getCurrentUserName(),
                            requesterAvatar = getCurrentAvatar()
                        )
                        eventBroadcaster?.invoke(event)
                        _sessionState.update { current ->
                            current.copy(
                                playbackStatus = SessionState.Status.PAUSED,
                                positionAtAnchor = state.currentPositionMs
                            )
                        }
                    }
                }
                
                // Detect track change - broadcast as PlayEvent to sync the new track
                if (lastMediaId != null && lastMediaId != state.mediaId && state.mediaId != null) {
                    Log.i(TAG, "Auto-broadcast: Host changed track to ${state.mediaId}")
                    val (title, artist, thumbnailUrl) = getCurrentMetadata()
                    val event = PlayEvent(
                        mediaId = state.mediaId,
                        startPos = state.currentPositionMs,
                        timestamp = now,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl,
                        requesterName = getCurrentUserName(),
                        requesterAvatar = getCurrentAvatar()
                    )
                    eventBroadcaster?.invoke(event)
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
                    Log.i(TAG, "Auto-broadcast: Host seeked to ${state.currentPositionMs}")
                    val event = SeekEvent(
                        pos = state.currentPositionMs,
                        timestamp = now,
                        requesterName = getCurrentUserName(),
                        requesterAvatar = getCurrentAvatar()
                    )
                    eventBroadcaster?.invoke(event)
                    _sessionState.update { current ->
                        current.copy(
                            positionAtAnchor = state.currentPositionMs,
                            trackStartGlobalTime = now
                        )
                    }
                }
            }
            
            // Update tracking variables
            lastIsPlaying = state.isPlaying
            lastMediaId = state.mediaId
            lastPosition = state.currentPositionMs
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
                    val ping = PingEvent(id = pingId, clientTimestamp = now, timestamp = now)
                    
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
        val targetPosition = if (isEffectivelyPlaying) {
            currentPos + SYNC_LEAD_TIME_MS
        } else {
            currentPos
        }
        
        // Get current metadata
        val (title, artist, thumbnailUrl) = getCurrentMetadata()
        
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
            playbackEngine.play()
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
        scope.launch {
            while (true) {
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
                        val actualPos = playbackEngine.playbackState.value.currentPositionMs
                        
                        val drift = kotlin.math.abs(actualPos - expectedPos)
                        if (drift > DRIFT_THRESHOLD_MS) {
                            Log.w(TAG, "DRIFT MONITOR: Significant drift detected! actual=$actualPos, expected=$expectedPos, drift=${drift}ms. Resyncing...")
                            applyAuthoritativeSnapshot(state)
                        } else {
                            Log.v(TAG, "DRIFT MONITOR: Drift within limits (${drift}ms)")
                        }
                    }
                }
            }
        }
    }

    fun joinSession(code: String) {
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
    }

    /**
     * Stops the current session and disconnects from transport.
     */
    fun stopSession() {
        Log.i(TAG, "stopSession: Stopping session, isHost=$isHost")
        stopHeartbeat()  // Stop heartbeat before disconnecting
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
            Log.d(TAG, "resume: BLOCKED (snapshot lock active)")
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
            Log.d(TAG, "seekTo: BLOCKED (snapshot lock active)")
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
            Log.d(TAG, "seekTo: Debounced (too fast)")
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
            Log.d(TAG, "onTrackChanged: Participant blocked (Host-Only Mode)")
            return
        }
        
        // Get metadata for the track
        val metadata = metadataProvider?.invoke()
        
        // Broadcast the track change (Host or Participant requesting new track)
        val now = timeSyncEngine.getGlobalTime()
        val scheduledStartTime = if (isHost) now + SYNC_LEAD_TIME_MS else now
        
        val event = PlayEvent(
            mediaId = mediaId,
            startPos = 0L,
            timestamp = scheduledStartTime,
            playbackSpeed = playbackEngine.playbackState.value.playbackSpeed,
            title = metadata?.first,
            artist = metadata?.second,
            thumbnailUrl = metadata?.third,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
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
        
        // Host should process all events (Control requests from participants + RequestState/Ping)
        // EXCEPT if Host-Only Mode is active - then ignore request events from participants
        if (isHost && _sessionState.value.hostOnlyMode && 
            event !is RequestStateEvent && event !is PingEvent && event !is JoinEvent) {
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
                applyAuthoritativeSnapshot(event.state)
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
                        thumbnailUrl = event.thumbnailUrl
                    )
                )
                // Aggressive Name Collection: Capture requester's name and avatar
                event.requesterName?.let { name ->
                    _sessionState.update { current ->
                        val updatedNames = current.connectedPeerNames.toMutableMap()
                        val updatedAvatars = current.connectedPeerAvatars.toMutableMap()
                        val key = senderId ?: name
                        if (!updatedNames.containsKey(key) || updatedNames[key] == "Connecting...") {
                            Log.i(TAG, "Aggressive Collection: Registering $name for key $key from PlayEvent")
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
                _sessionState.update { current ->
                    current.copy(
                        playbackStatus = SessionState.Status.PAUSED,
                        positionAtAnchor = event.pos,
                        trackStartGlobalTime = timeSyncEngine.getGlobalTime()
                    )
                }
                applyAuthoritativeSnapshot(_sessionState.value)
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
                applyAuthoritativeSnapshot(_sessionState.value)
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
                Log.d(TAG, "processEvent: PingEvent received - id=${event.id}")
                
                // PHASE 2 FIX: BOTH host and participant must respond with pongs
                // This is critical for bidirectional heartbeat validation
                val now = timeSyncEngine.getGlobalTime()
                val pong = PongEvent(
                    id = event.id,
                    clientTimestamp = event.clientTimestamp,
                    serverTimestamp = event.timestamp,
                    serverReplyTimestamp = now,
                    timestamp = now
                )
                val role = if (isHost) "HOST" else "PARTICIPANT"
                Log.d(TAG, "processEvent: $role sending PongEvent for ping ${event.id}")
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
                }
            }
        }
    }

    // ============================================================
    // AUTHORITATIVE APPLY (CORE FIX)
    // ============================================================

    private fun applyAuthoritativeSnapshot(state: SessionState) {
        // CRITICAL FIX: Wrap entire apply in Mutex to prevent concurrent modification
        // This guarantees that version checks and state updates are atomic
        scope.launch {
            applyLock.withLock {
                try {
                    Log.i(TAG, "applyAuthoritativeSnapshot: Acquired lock - applying state v${state.stateVersion} (hostUid=${state.hostUid})")
                    
                    // VERSION CHECK INSIDE LOCK - Prevents TOCTOU race where newer snapshot arrives during apply
                    if (!isHost && state.stateVersion > 0 && state.stateVersion <= lastAppliedVersion) {
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
                    
                    // EVENT DEDUPLICATION - Skip if this is effectively the same state
                    val currentPos = playbackEngine.playbackState.value.currentPositionMs
                    val positionDrift = kotlin.math.abs(state.positionAtAnchor - currentPos)
                    val currentMediaId = playbackEngine.playbackState.value.mediaId
                    val currentIsPlaying = playbackEngine.playbackState.value.isPlaying
                    val currentStatus = if (currentIsPlaying) SessionState.Status.PLAYING else SessionState.Status.PAUSED
                    
                    if (state.currentMediaId == lastAppliedMediaId &&
                        state.playbackStatus == lastAppliedStatus &&
                        state.currentMediaId == currentMediaId &&
                        state.playbackStatus == currentStatus &&
                        positionDrift < POSITION_DRIFT_THRESHOLD_MS &&
                        (System.currentTimeMillis() - lastAppliedTimestamp) < DEDUP_THRESHOLD_MS) {
                        Log.d(TAG, "applyAuthoritativeSnapshot: IGNORED (duplicate state, drift=${positionDrift}ms)")
                        return@withLock
                    }
                    
                    // Mark this version as being applied (prevents newer snapshots from being rejected)
                    currentApplyingVersion = state.stateVersion
                    
                    // Update deduplication tracking
                    lastAppliedMediaId = state.currentMediaId
                    lastAppliedStatus = state.playbackStatus
                    lastAppliedPosition = state.positionAtAnchor
                    lastAppliedTimestamp = System.currentTimeMillis()
                    if (state.stateVersion > 0) {
                        lastAppliedVersion = state.stateVersion
                    }
                    
                    // Set flag to prevent onTrackChanged from broadcasting during this apply
                    isApplyingSnapshot = true
        
        // CRITICAL: Preserve local isHost, sessionId, and connectedPeers - don't copy from remote state!
        _sessionState.update { current ->
            val mergedNames = state.connectedPeerNames.toMutableMap()
            
            // Aggressive name collection: if the Host's list is empty, something might be wrong with the broadcast
            // But if it has names, we adopt them.
            
            // Always preserve local name if it's missing from Host's list
            getCurrentUserName()?.let { myName ->
                if (myName != "Unknown" && !mergedNames.containsKey(myName)) {
                    mergedNames[myName] = myName
                }
            }

            state.copy(
                isHost = current.isHost,
                sessionId = current.sessionId,
                hostUid = if (current.isHost) current.hostUid else state.hostUid,
                connectedPeers = current.connectedPeers,
                // FIX: Participants adopted Host names PLUS their own identity
                connectedPeerNames = if (current.isHost) current.connectedPeerNames else mergedNames,
                connectedPeerUids = if (current.isHost) current.connectedPeerUids else state.connectedPeerUids
            )
        }
        val names = _sessionState.value.connectedPeerNames.values.joinToString(", ")
        Log.i(TAG, "applyAuthoritativeSnapshot: Snapshot applied. Combined names: [$names]")
        Log.d(TAG, "applyAuthoritativeSnapshot: Local state preserved (isHost=${_sessionState.value.isHost})")

        val now = timeSyncEngine.getGlobalTime()
        
        // Use unified calculator for target position
        val targetPos = calculateParticipantPosition(
            anchorPos = state.positionAtAnchor,
            anchorTime = state.trackStartGlobalTime,
            speed = state.playbackSpeed
        )

        // Check if we need to load a new track or just update playback state
        val isSameTrack = currentMediaId == state.currentMediaId && state.currentMediaId != null
        
        if (isSameTrack) {
            // SAME TRACK - just update playback state without reloading
            Log.d(TAG, "applyAuthoritativeSnapshot: Same track, updating playback state only")
            
            // FIX 7: SEEK STABILITY - Only seek if drift > threshold
            val needsSeek = positionDrift > POSITION_DRIFT_THRESHOLD_MS
            
            when (state.playbackStatus) {
                SessionState.Status.PLAYING -> {
                    if (!isHost) {
                        // PRE-BUFFER SYNC: Play ahead for smooth buffer, then seamlessly resync
                        val receivedAt = timeSyncEngine.getGlobalTime()  // When we received this event
                        val elapsedSinceAnchor = receivedAt - state.trackStartGlobalTime
                        
                        // BUG FIX: For scheduled future tracks (track transitions), elapsedSinceAnchor is NEGATIVE
                        // This causes hostPosWhenReceived to be negative, leading to wrong prebuffer position
                        // Clamp to 0 to ensure new tracks always start from beginning
                        val hostPosWhenReceived = kotlin.math.max(0L, state.positionAtAnchor + elapsedSinceAnchor)
                        
                        Log.i(TAG, "applyAuthoritativeSnapshot: PRE-BUFFER SYNC - hostPos=${hostPosWhenReceived}ms at receive time (elapsed=${elapsedSinceAnchor}ms)")
                        _sessionState.update { it.copy(
                            syncStatus = SessionState.SyncStatus.SYNCING,
                            clockSyncMessage = "Buffering..."
                        ) }
                        
                        // Step 1: Seek ahead and PLAY (not pause!) to fill buffer
                        val bufferAheadMs = SYNC_LEAD_TIME_MS  // 4 seconds ahead
                        val prebufferPos = hostPosWhenReceived + bufferAheadMs
                        Log.i(TAG, "applyAuthoritativeSnapshot: Playing at ${prebufferPos}ms for pre-buffer")
                        playbackEngine.seekTo(prebufferPos)
                        playbackEngine.play()  // Keep playing to fill audio buffer
                        
                        // Step 2 & 3: Wait for buffer then resync with 800ms LEAD
                        scope.launch {
                            // Allow buffer time while playing ahead
                            val bufferDurationMs = 2000L
                            delay(bufferDurationMs)
                            
                            // Step 3: Calculate EXACT position with 450ms LEAD using unified calculator
                            val syncNow = timeSyncEngine.getGlobalTime()
                            val finalCorrectPos = calculateParticipantPosition(
                                anchorPos = state.positionAtAnchor,
                                anchorTime = state.trackStartGlobalTime,
                                speed = state.playbackSpeed
                            )
                            
                            Log.i(TAG, "applyAuthoritativeSnapshot: RESYNC to ${finalCorrectPos}ms (${PARTICIPANT_LEAD_TIME_MS}ms lead applied via unified calculator)")
                            _sessionState.update { it.copy(
                                clockSyncMessage = "Syncing..."
                            ) }
                            
                            // Step 4: Seamlessly seek to correct position
                            playbackEngine.seekTo(finalCorrectPos)
                            
                            _sessionState.update { it.copy(
                                syncStatus = SessionState.SyncStatus.READY,
                                clockSyncMessage = "Playing in sync! 🎵"
                            ) }
                            Log.i(TAG, "applyAuthoritativeSnapshot: PRE-BUFFER SYNC COMPLETE! Now at ${finalCorrectPos}ms ($PARTICIPANT_LEAD_TIME_MS ms lead)")
                        }
                    } else {
                        // HOST: Seek if needed, then play
                        if (needsSeek) {
                            Log.d(TAG, "applyAuthoritativeSnapshot: HOST seeking to $targetPos (drift=${positionDrift}ms)")
                            playbackEngine.seekTo(targetPos)
                        }
                        if (!currentIsPlaying) {
                            playbackEngine.play()
                        }
                    }
                }
                SessionState.Status.PAUSED -> {
                    if (currentIsPlaying) {
                        playbackEngine.pause()
                    }
                    if (needsSeek) {
                        Log.d(TAG, "applyAuthoritativeSnapshot: Seeking to $targetPos (drift=${positionDrift}ms)")
                        playbackEngine.seekTo(targetPos)
                    }
                }
                else -> {
                    Log.d(TAG, "applyAuthoritativeSnapshot: Idle/Unknown status, pausing")
                    playbackEngine.pause()
                }
            }
        } else {
            // DIFFERENT TRACK - need to load new track
            Log.d(TAG, "applyAuthoritativeSnapshot: Different track, loading new media")
            playbackEngine.pause()
            
            val shouldAutoPlay = state.playbackStatus == SessionState.Status.PLAYING
            val scheduledStartTime = state.trackStartGlobalTime
            val now = timeSyncEngine.getGlobalTime()
            
            state.currentMediaId?.let { mediaId ->
                // Mark this track as synced to suppress echo broadcast when async load completes
                lastSyncedMediaId = mediaId
                lastSyncedTimestamp = System.currentTimeMillis()
                
                if (shouldAutoPlay && scheduledStartTime > now) {
                    // PERFECT INITIAL SYNC: Load, buffer, wait, then start at exactly the right moment
                    Log.i(TAG, "applyAuthoritativeSnapshot: SCHEDULED TRACK LOAD - waiting ${scheduledStartTime - now}ms after load")
                    playbackEngine.loadTrack(mediaId, state.positionAtAnchor, autoPlay = false)
                    
                    scope.launch {
                        waitForScheduledTime(scheduledStartTime)
                        
                        // Use unified calculator for scheduled start position
                        val startPos = calculateParticipantPosition(
                            anchorPos = state.positionAtAnchor,
                            anchorTime = scheduledStartTime,  // Use scheduled time as anchor
                            speed = state.playbackSpeed
                        )
                        
                        if (!isHost) {
                            Log.i(TAG, "applyAuthoritativeSnapshot: Scheduled start - seeking to $startPos (lead applied via unified calculator)")
                            playbackEngine.seekTo(startPos)
                        }
                        
                        playbackEngine.play()
                        Log.i(TAG, "applyAuthoritativeSnapshot: Track started at scheduled time!")
                    }
                } else {
                    // Normal load (paused state or late joiner)
                    Log.i(TAG, "applyAuthoritativeSnapshot: Loading track - $mediaId, seekTo=$targetPos, autoPlay=$shouldAutoPlay")
                    playbackEngine.loadTrack(mediaId, targetPos, shouldAutoPlay)
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
    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }
}


