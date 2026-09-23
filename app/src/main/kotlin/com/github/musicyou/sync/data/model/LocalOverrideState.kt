package com.github.musicyou.sync.data.model

enum class OverrideReason {
    FILE_NOT_FOUND,
    MANUAL_USER_CHOICE,
    FORMAT_UNSUPPORTED
}

/**
 * First-class state representing a participant's deliberate local file override.
 * When present, incoming authoritative snapshots from the host will NOT clear the local file,
 * and the drift monitor will use a loosened threshold (e.g. 3000ms) without fingerprint validation.
 */
data class LocalOverrideState(
    val manualUri: String,
    val title: String = "Local File",
    val durationMs: Long = 0L,
    val reason: OverrideReason = OverrideReason.MANUAL_USER_CHOICE,
    val isDeliberateMismatch: Boolean = true,
    val boundTimestamp: Long = System.currentTimeMillis()
)
