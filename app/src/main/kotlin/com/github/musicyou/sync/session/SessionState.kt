package com.github.musicyou.sync.session

/**
 * Immutable representation of the global session state.
 * This is the "Truth" that is synchronized across devices.
 * 
 * @property playbackStatus Current status (PLAYING, PAUSED).
 * @property currentMediaId The media ID currently loaded.
 * @property playbackSpeed The speed of playback (default 1.0).
 * @property timestamp The Global Time at which this state was valid.
 * @property position The Playback Position at `timestamp`.
 */
data class SessionState(
    val sessionId: String? = null,
    val isHost: Boolean = false,
    val hostUid: String? = null, // The Firebase UID of the host
    val connectedPeers: Set<String> = emptySet(),

    val currentMediaId: String? = null,
    val playbackStatus: Status = Status.PAUSED,

    // Anchor-based sync
    val trackStartGlobalTime: Long = 0L,   // when playback started globally
    val positionAtAnchor: Long = 0L,       // position at that time (ms)

    val playbackSpeed: Float = 1.0f,
    
    // Metadata for UI display on participant
    val title: String? = null,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    
    // Control Mode
    val hostOnlyMode: Boolean = false,
    
    // Connected peer names for display (Map of peerId to name)
    val connectedPeerNames: Map<String, String> = emptyMap(),
    
    // Connected peer avatars (Map of peerId to photoUrl)
    val connectedPeerAvatars: Map<String, String?> = emptyMap(),
    
    // Connected peer UIDs (Map of peerId to Firebase UID)
    val connectedPeerUids: Map<String, String?> = emptyMap(),
    
    // Version control - monotonically increasing, set by Host
    val stateVersion: Long = 0L,
    
    // Sync status for UX feedback
    val syncStatus: SyncStatus = SyncStatus.WAITING,
    
    val isHandshaking: Boolean = false,
    
    // Clock sync status message
    val clockSyncMessage: String? = null
) {
    enum class Status { PLAYING, PAUSED }
    
    /**
     * Sync status for UX feedback.
     * WAITING - Host waiting for participants, Participant connecting
     * SYNCING - Clock calibration in progress
     * READY - Ready to start synchronized playback
     * ERROR - Connection lost or critical failure
     */
    enum class SyncStatus {
        WAITING,   // Host: waiting for participants, Participant: connecting
        SYNCING,   // Clock calibration in progress
        READY,     // Ready for synchronized playback
        ERROR      // Connection lost, timeout, or critical failure
    }
}

