package com.github.musicyou.sync.protocol

import com.github.musicyou.sync.session.SessionState

/**
 * Events used to synchronize state across devices.
 * All timestamped events use Global Time.
 */
sealed class SyncEvent {
    abstract val timestamp: Long
}

/**
 * Request/Command to start playback.
 * @property mediaId The media to play.
 * @property startPos The position to start from.
 * @property timestamp Global time when this command was issued/should take effect.
 * @property title Song title (optional, for UI display on participant).
 * @property artist Artist name (optional, for UI display on participant).
 * @property thumbnailUrl Thumbnail URL (optional, for UI display on participant).
 * @property requesterName Name of user requesting this action (for toast notification).
 */
data class PlayEvent(
    val mediaId: String,
    val startPos: Long,
    override val timestamp: Long,
    val playbackSpeed: Float = 1.0f,
    val title: String? = null,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val requesterName: String? = null,
    val requesterAvatar: String? = null
) : SyncEvent()

/**
 * Request/Command to pause playback.
 * @property pos The position at which to pause.
 * @property timestamp Global time when the pause occurred.
 * @property requesterName Name of user requesting this action (for toast notification).
 */
data class PauseEvent(
    val pos: Long,
    override val timestamp: Long,
    val requesterName: String? = null,
    val requesterAvatar: String? = null
) : SyncEvent()

/**
 * Request/Command to seek.
 * @property pos The target position.
 * @property timestamp Global time when the seek occurred.
 * @property requesterName Name of user requesting this action (for toast notification).
 */
data class SeekEvent(
    val pos: Long,
    override val timestamp: Long,
    val requesterName: String? = null,
    val requesterAvatar: String? = null
) : SyncEvent()

/**
 * Full state synchronization (e.g. for new joiners).
 */
data class StateSyncEvent(
    val state: SessionState,
    override val timestamp: Long
) : SyncEvent()

/**
 * Request from a participant to fetch the current session state.
 * Host should respond with StateSyncEvent.
 */
data class RequestStateEvent(
    override val timestamp: Long,
    val senderName: String? = null,   // Name of the joining participant
    val senderAvatar: String? = null,  // Avatar URL of the joining participant
    val senderUid: String? = null     // Firebase UID of the joining participant
) : SyncEvent()

/**
 * Announcement when a participant joins with their name.
 * @property name Display name of the joining user.
 */
data class JoinEvent(
    val name: String,
    val avatar: String? = null,
    val uid: String? = null,
    override val timestamp: Long
) : SyncEvent()

/**
 * Time Synchronization PING.
 * @property id Unique ID to match PONG.
 * @property clientTimestamp Time when PING was sent (t0).
 */
data class PingEvent(
    val id: String,
    val clientTimestamp: Long,
    override val timestamp: Long // Same as clientTimestamp, required by sealed class
) : SyncEvent()

/**
 * Time Synchronization PONG.
 * @property id Unique ID matching the PING.
 * @property clientTimestamp Time when PING was sent (t0).
 * @property serverTimestamp Time when PING was received (t1).
 * @property serverReplyTimestamp Time when PONG was sent (t2).
 */
data class PongEvent(
    val id: String,
    val clientTimestamp: Long,
    val serverTimestamp: Long,
    val serverReplyTimestamp: Long,
    override val timestamp: Long // serverReplyTimestamp
) : SyncEvent()
