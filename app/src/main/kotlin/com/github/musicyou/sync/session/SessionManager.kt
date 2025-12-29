package com.github.musicyou.sync.session

import android.util.Log
import com.github.musicyou.sync.playback.PlaybackEngine
import com.github.musicyou.sync.protocol.*
import com.github.musicyou.sync.time.TimeSyncEngine
import com.github.musicyou.sync.transport.TransportLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        private const val SYNC_ECHO_SUPPRESS_MS = 3000L   // Suppress echo for 3 seconds after sync
        private const val SYNC_LEAD_TIME_MS = 300L        // Schedule playback start 300ms in future for sync
        private const val DEBOUNCE_MS = 500L              // Ignore rapid button presses within 500ms
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

    init {
        Log.d(TAG, "init: SessionManager created")
        
        // Sync transport layer's sessionId and connectedPeers to sessionState
        transportLayer.sessionId.onEach { sessionId ->
            Log.d(TAG, "init: sessionId changed to: $sessionId")
            _sessionState.value = _sessionState.value.copy(sessionId = sessionId)
            
            // STRICT RULE: If Host disconnects (sessionId null), Participant must stop playback.
            if (sessionId == null && !isHost) {
                Log.i(TAG, "init: Session ended (disconnected). Stopping local playback.")
                playbackEngine.pause()
                playbackEngine.seekTo(0)
            }
        }.launchIn(scope)
        
        transportLayer.connectedPeers.onEach { peers ->
            Log.i(TAG, "init: connectedPeers changed to: $peers (count=${peers.size})")
            val previousPeerCount = _sessionState.value.connectedPeers.size
            _sessionState.value = _sessionState.value.copy(connectedPeers = peers.toSet())
            
            // HOST: (MANDATORY) When a new participant connects, immediately send a full StateSyncEvent snapshot
            if (isHost && peers.size > previousPeerCount && peers.isNotEmpty()) {
                Log.i(TAG, "init: HOST detected new peer joining, sending StateSyncEvent")
                // Small delay to ensure participant is ready to receive
                kotlinx.coroutines.delay(500)
                // Get current metadata to include in the state
                val (title, artist, thumbnailUrl) = getCurrentMetadata()
                val state = _sessionState.value.copy(
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumbnailUrl
                )
                val now = timeSyncEngine.getGlobalTime()
                val syncEvent = StateSyncEvent(state, now)
                Log.i(TAG, "init: HOST broadcasting StateSyncEvent - mediaId=${state.currentMediaId}, title=$title, status=${state.playbackStatus}")
                eventBroadcaster?.invoke(syncEvent)
            }
            
            // PARTICIPANT: When connecting to host, send join announcement and request state
            if (!isHost && peers.isNotEmpty() && previousPeerCount == 0) {
                val myName = getCurrentUserName()
                Log.i(TAG, "init: Participant detected connection, sending JoinEvent and RequestStateEvent (name=$myName)")
                
                // Send JoinEvent to announce our name
                myName?.let {
                    val joinEvent = JoinEvent(name = it, timestamp = timeSyncEngine.getGlobalTime())
                    eventBroadcaster?.invoke(joinEvent)
                }
                
                // Request the current state
                val requestEvent = RequestStateEvent(
                    timestamp = timeSyncEngine.getGlobalTime(),
                    senderName = myName
                )
                eventBroadcaster?.invoke(requestEvent)
            }
        }.launchIn(scope)
        
        // CORE FIX: Listen for playback state changes and broadcast when host is connected
        var lastIsPlaying: Boolean? = null
        var lastMediaId: String? = null
        var lastPosition: Long = 0L
        
        playbackEngine.playbackState.onEach { state ->
            // Only broadcast if we are host with connected peers
            if (isHost && _sessionState.value.connectedPeers.isNotEmpty()) {
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
                            thumbnailUrl = thumbnailUrl
                        )
                        eventBroadcaster?.invoke(event)
                        _sessionState.value = _sessionState.value.copy(
                            currentMediaId = state.mediaId,
                            playbackStatus = SessionState.Status.PLAYING,
                            trackStartGlobalTime = now,
                            positionAtAnchor = state.currentPositionMs,
                            title = title,
                            artist = artist,
                            thumbnailUrl = thumbnailUrl
                        )
                    } else {
                        Log.i(TAG, "Auto-broadcast: Host paused")
                        val event = PauseEvent(
                            pos = state.currentPositionMs,
                            timestamp = now
                        )
                        eventBroadcaster?.invoke(event)
                        _sessionState.value = _sessionState.value.copy(
                            playbackStatus = SessionState.Status.PAUSED,
                            positionAtAnchor = state.currentPositionMs
                        )
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
                        thumbnailUrl = thumbnailUrl
                    )
                    eventBroadcaster?.invoke(event)
                    _sessionState.value = _sessionState.value.copy(
                        currentMediaId = state.mediaId,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl
                    )
                }
                
                // Detect significant seek (more than 2 seconds difference)
                val positionDiff = kotlin.math.abs(state.currentPositionMs - lastPosition)
                if (!isSeeking && lastIsPlaying == state.isPlaying && positionDiff > 2000) {
                    Log.i(TAG, "Auto-broadcast: Host seeked to ${state.currentPositionMs}")
                    val event = SeekEvent(
                        pos = state.currentPositionMs,
                        timestamp = now
                    )
                    eventBroadcaster?.invoke(event)
                    _sessionState.value = _sessionState.value.copy(
                        positionAtAnchor = state.currentPositionMs,
                        trackStartGlobalTime = now
                    )
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
     * Start periodic heartbeat to keep connection alive.
     * Sends PingEvent every HEARTBEAT_INTERVAL_MS to prevent WebRTC timeout.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            Log.i(TAG, "startHeartbeat: Starting periodic heartbeat (${HEARTBEAT_INTERVAL_MS}ms interval)")
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_sessionState.value.sessionId != null) {
                    val now = timeSyncEngine.getGlobalTime()
                    val pingId = "heartbeat-${System.currentTimeMillis()}"
                    val ping = PingEvent(id = pingId, clientTimestamp = now, timestamp = now)
                    Log.d(TAG, "heartbeat: Sending ping $pingId")
                    eventBroadcaster?.invoke(ping)
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


    // ============================================================
    // HOST CONTROLS
    // ============================================================

    fun startSession() {
        Log.i(TAG, "startSession: Starting as HOST")
        isHost = true
        val now = timeSyncEngine.getGlobalTime()

        val engine = playbackEngine.playbackState.value
        Log.d(TAG, "startSession: Current playback - mediaId=${engine.mediaId}, isPlaying=${engine.isPlaying}, pos=${engine.currentPositionMs}")

        _sessionState.value = SessionState(
            isHost = true,
            currentMediaId = engine.mediaId,
            playbackStatus = if (engine.isPlaying) SessionState.Status.PLAYING else SessionState.Status.PAUSED,
            trackStartGlobalTime = now - engine.currentPositionMs,
            positionAtAnchor = 0L,
            playbackSpeed = engine.playbackSpeed
        )
        Log.d(TAG, "startSession: SessionState set - ${_sessionState.value}")

        scope.launch { transportLayer.connect(null) }
        
        // Start heartbeat to keep connection alive
        startHeartbeat()
    }

    fun joinSession(code: String) {
        Log.i(TAG, "joinSession: Joining as PARTICIPANT with code=$code")
        isHost = false
        // Also set the StateFlow to keep in sync
        _sessionState.value = _sessionState.value.copy(isHost = false)
        scope.launch { transportLayer.connect(code) }
        
        // Start heartbeat to keep connection alive
        startHeartbeat()
    }

    /**
     * Stops the current session and disconnects from transport.
     */
    fun stopSession() {
        Log.i(TAG, "stopSession: Stopping session, isHost=$isHost")
        stopHeartbeat()  // Stop heartbeat before disconnecting
        scope.launch { transportLayer.disconnect() }
        _sessionState.value = SessionState() // Reset to default
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
        _sessionState.value = _sessionState.value.copy(hostOnlyMode = enabled)
        
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
        
        // Schedule playback start for future time to allow sync
        val scheduledStartTime = if (isHost) now + SYNC_LEAD_TIME_MS else now
        
        val event = PlayEvent(
            mediaId = state.mediaId ?: return,
            startPos = state.currentPositionMs,
            timestamp = scheduledStartTime,  // Future time for scheduled start
            playbackSpeed = state.playbackSpeed,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
        )
        // Send event first!
        eventBroadcaster?.invoke(event)
        
        // Host schedules local playback for the same future time
        if (isHost) {
            scope.launch {
                val waitTime = scheduledStartTime - timeSyncEngine.getGlobalTime()
                if (waitTime > 0) {
                    Log.d(TAG, "resume: Host waiting ${waitTime}ms before playing")
                    delay(waitTime)
                }
                playbackEngine.play()
                
                // Update session state
                _sessionState.value = _sessionState.value.copy(
                    playbackStatus = SessionState.Status.PLAYING,
                    trackStartGlobalTime = scheduledStartTime,
                    positionAtAnchor = state.currentPositionMs
                )
            }
        }
    }

    /**
     * Pause playback. Broadcasts PauseEvent to everyone.
     */
    fun pause() {
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
        
        if (isHost) {
            playbackEngine.pause()
        }
        
        val state = playbackEngine.playbackState.value
        val now = timeSyncEngine.getGlobalTime()
        val event = PauseEvent(
            pos = state.currentPositionMs,
            timestamp = now,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
        )
        eventBroadcaster?.invoke(event)
        
        if (isHost) {
            _sessionState.value = _sessionState.value.copy(
                playbackStatus = SessionState.Status.PAUSED,
                positionAtAnchor = state.currentPositionMs
            )
        }
    }

    /**
     * Seek to position. Broadcasts SeekEvent to everyone.
     * Also auto-resumes playback for better UX.
     */
    fun seekTo(positionMs: Long) {
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
            Log.i(TAG, "seekTo: Broadcasting SeekEvent - pos=${event.pos}")
            eventBroadcaster?.invoke(event)
            
            if (isHost) {
                _sessionState.value = _sessionState.value.copy(
                    positionAtAnchor = positionMs,
                    trackStartGlobalTime = now,
                    playbackStatus = SessionState.Status.PLAYING  // Update status since we auto-resumed
                )
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
        val event = PlayEvent(
            mediaId = mediaId,
            startPos = 0L,
            timestamp = now,
            playbackSpeed = playbackEngine.playbackState.value.playbackSpeed,
            title = metadata?.first,
            artist = metadata?.second,
            thumbnailUrl = metadata?.third,
            requesterName = getCurrentUserName()  // Always include name (Host or Participant)
        )
        
        if (isHost) {
            Log.i(TAG, "onTrackChanged: HOST Broadcasting PlayEvent for new track - mediaId=$mediaId")
            _sessionState.value = _sessionState.value.copy(
                currentMediaId = mediaId,
                playbackStatus = SessionState.Status.PLAYING,
                trackStartGlobalTime = now,
                positionAtAnchor = 0L
            )
        } else {
            Log.i(TAG, "onTrackChanged: PARTICIPANT requesting track change - mediaId=$mediaId, title=${metadata?.first}")
        }
        
        eventBroadcaster?.invoke(event)
    }

    // ============================================================
    // EVENT HANDLING
    // ============================================================

    fun processEvent(event: SyncEvent) {
        Log.i(TAG, "processEvent: Received ${event::class.simpleName}, isHost=$isHost")
        
        // Host should process all events (Control requests from participants + RequestState/Ping)
        // EXC EPT if Host-Only Mode is active - then ignore request events from participants
        if (isHost && _sessionState.value.hostOnlyMode && event !is RequestStateEvent && event !is PingEvent) {
            Log.d(TAG, "processEvent: Host ignoring participant request event due to Host-Only Mode: ${event::class.simpleName}")
            return
        }


        when (event) {
            is RequestStateEvent -> {
                Log.i(TAG, "processEvent: RequestStateEvent received from ${event.senderName}")
                if (!isHost) {
                    Log.d(TAG, "processEvent: Participant ignoring RequestStateEvent")
                    return
                }
                
                // Track sender's name if provided
                event.senderName?.let { name ->
                    val updatedNames = _sessionState.value.connectedPeerNames.toMutableMap()
                    // Use "participant" as a generic key since we don't have peer ID here
                    updatedNames["latest_participant"] = name
                    _sessionState.value = _sessionState.value.copy(connectedPeerNames = updatedNames)
                    Log.i(TAG, "processEvent: Tracked participant name: $name")
                }

                val state = _sessionState.value
                val now = timeSyncEngine.getGlobalTime()
                Log.i(TAG, "processEvent: HOST responding with StateSyncEvent - mediaId=${state.currentMediaId}, status=${state.playbackStatus}")
                eventBroadcaster?.invoke(StateSyncEvent(state, now))
            }
            
            is JoinEvent -> {
                Log.i(TAG, "processEvent: JoinEvent received - name=${event.name}")
                // Update connected peer names
                val updatedNames = _sessionState.value.connectedPeerNames.toMutableMap()
                updatedNames["participant_${System.currentTimeMillis()}"] = event.name
                _sessionState.value = _sessionState.value.copy(connectedPeerNames = updatedNames)
                Log.i(TAG, "processEvent: Added peer name: ${event.name}, total: ${updatedNames.size}")
                
                // Show toast that someone joined
                toastHandler?.invoke("${event.name} joined the session")
            }

            is StateSyncEvent -> {
                Log.i(TAG, "processEvent: StateSyncEvent received - mediaId=${event.state.currentMediaId}, status=${event.state.playbackStatus}")
                // Authoritative snapshot override
                applyAuthoritativeSnapshot(event.state)
            }

            is PlayEvent -> {
                Log.i(TAG, "processEvent: PlayEvent received - mediaId=${event.mediaId}, title=${event.title}, startPos=${event.startPos}, requester=${event.requesterName}")
                applyAuthoritativeSnapshot(
                    SessionState(
                        currentMediaId = event.mediaId,
                        playbackStatus = SessionState.Status.PLAYING,
                        trackStartGlobalTime = event.timestamp,
                        positionAtAnchor = event.startPos,
                        playbackSpeed = event.playbackSpeed,
                        title = event.title,
                        artist = event.artist,
                        thumbnailUrl = event.thumbnailUrl
                    )
                )
                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name played: ${event.title ?: "track"}")
                    }
                }
                // If Host received this from participant, broadcast authoritative EVENT back
                if (isHost) {
                    val now = timeSyncEngine.getGlobalTime()
                    Log.i(TAG, "processEvent: HOST broadcasting Authoritative PlayEvent from requester=${event.requesterName}")
                    eventBroadcaster?.invoke(event)
                    
                    Log.i(TAG, "processEvent: HOST broadcasting StateSyncEvent after PlayEvent")
                    eventBroadcaster?.invoke(StateSyncEvent(_sessionState.value, now))
                }
            }

            is PauseEvent -> {
                Log.i(TAG, "processEvent: PauseEvent received - pos=${event.pos}, requester=${event.requesterName}")
                applyAuthoritativeSnapshot(
                    _sessionState.value.copy(
                        playbackStatus = SessionState.Status.PAUSED
                    )
                )
                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name paused playback")
                    }
                }
                // If Host received this from participant, broadcast authoritative EVENT back
                if (isHost) {
                    val now = timeSyncEngine.getGlobalTime()
                    Log.i(TAG, "processEvent: HOST broadcasting Authoritative PauseEvent from requester=${event.requesterName}")
                    eventBroadcaster?.invoke(event)
                    
                    Log.i(TAG, "processEvent: HOST broadcasting StateSyncEvent after PauseEvent")
                    eventBroadcaster?.invoke(StateSyncEvent(_sessionState.value, now))
                }
            }

            is SeekEvent -> {
                Log.i(TAG, "processEvent: SeekEvent received - pos=${event.pos}, requester=${event.requesterName}")
                applyAuthoritativeSnapshot(
                    _sessionState.value.copy(
                        positionAtAnchor = event.pos,
                        trackStartGlobalTime = timeSyncEngine.getGlobalTime()
                    )
                )
                // Show toast notification for action
                event.requesterName?.let { name ->
                    if (name == getCurrentUserName()) {
                         toastHandler?.invoke("Host accepted your request")
                    } else {
                         toastHandler?.invoke("$name seeked to ${formatTime(event.pos)}")
                    }
                }
                // If Host received this from participant, broadcast authoritative EVENT back
                if (isHost) {
                    val now = timeSyncEngine.getGlobalTime()
                    Log.i(TAG, "processEvent: HOST broadcasting Authoritative SeekEvent from requester=${event.requesterName}")
                    eventBroadcaster?.invoke(event)
                    
                    Log.i(TAG, "processEvent: HOST broadcasting StateSyncEvent after SeekEvent")
                    eventBroadcaster?.invoke(StateSyncEvent(_sessionState.value, now))
                }
            }

            is PingEvent -> {
                Log.d(TAG, "processEvent: PingEvent received - id=${event.id}")
                // Host responds with Pong for time sync
                if (isHost) {
                    val now = timeSyncEngine.getGlobalTime()
                    val pong = PongEvent(
                        id = event.id,
                        clientTimestamp = event.clientTimestamp,
                        serverTimestamp = event.timestamp,
                        serverReplyTimestamp = now,
                        timestamp = now
                    )
                    Log.d(TAG, "processEvent: HOST sending PongEvent")
                    eventBroadcaster?.invoke(pong)
                }
            }

            is PongEvent -> {
                Log.d(TAG, "processEvent: PongEvent received - id=${event.id}")
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
        Log.i(TAG, "applyAuthoritativeSnapshot: Applying state - mediaId=${state.currentMediaId}, status=${state.playbackStatus}, pos=${state.positionAtAnchor}")
        
        // Set flag to prevent onTrackChanged from broadcasting during this apply
        isApplyingSnapshot = true
        
        try {
        
        // CRITICAL: Preserve local isHost, sessionId, and connectedPeers - don't copy from remote state!
        val preservedIsHost = _sessionState.value.isHost
        val preservedSessionId = _sessionState.value.sessionId
        val preservedPeers = _sessionState.value.connectedPeers
        val currentMediaId = playbackEngine.playbackState.value.mediaId
        
        _sessionState.value = state.copy(
            isHost = preservedIsHost,
            sessionId = preservedSessionId,
            connectedPeers = preservedPeers
        )
        Log.d(TAG, "applyAuthoritativeSnapshot: Preserved local isHost=$preservedIsHost")

        val now = timeSyncEngine.getGlobalTime()
        val targetPos =
            if (state.playbackStatus == SessionState.Status.PLAYING) {
                state.positionAtAnchor +
                        ((now - state.trackStartGlobalTime) * state.playbackSpeed).toLong()
            } else {
                state.positionAtAnchor
            }

        // Check if we need to load a new track or just update playback state
        val isSameTrack = currentMediaId == state.currentMediaId && state.currentMediaId != null
        
        if (isSameTrack) {
            // SAME TRACK - just update playback state without reloading
            Log.d(TAG, "applyAuthoritativeSnapshot: Same track, updating playback state only")
            
            when (state.playbackStatus) {
                SessionState.Status.PLAYING -> {
                    Log.d(TAG, "applyAuthoritativeSnapshot: Seeking to $targetPos and playing")
                    playbackEngine.seekTo(targetPos)
                    playbackEngine.play()
                }
                SessionState.Status.PAUSED -> {
                    Log.d(TAG, "applyAuthoritativeSnapshot: Pausing and seeking to $targetPos")
                    playbackEngine.pause()
                    playbackEngine.seekTo(targetPos)
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
            
            state.currentMediaId?.let {
                Log.i(TAG, "applyAuthoritativeSnapshot: Loading track - $it, seekTo=$targetPos, autoPlay=$shouldAutoPlay")
                // Mark this track as synced to suppress echo broadcast when async load completes
                lastSyncedMediaId = it
                lastSyncedTimestamp = System.currentTimeMillis()
                playbackEngine.loadTrack(it, targetPos, shouldAutoPlay)
            }
        }
        
        Log.i(TAG, "applyAuthoritativeSnapshot: DONE - mediaId=${state.currentMediaId}, status=${state.playbackStatus}")
        
        } finally {
            // Always reset the flag when done
            isApplyingSnapshot = false
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


