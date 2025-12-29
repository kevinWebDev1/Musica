package com.github.musicyou.sync.time

/**
 * Snapshot of the current clock synchronization state.
 */
data class ClockState(
    /**
     * difference between local time and global (Host) time.
     * globalTime = localTime + offset
     * Positive offset means local clock is BEHIND host.
     */
    val offset: Long = 0L,

    /**
     * Round Trip Time of the last successful sync.
     */
    val rtt: Long = 0L,

    /**
     * Whether we have successfully synced at least once.
     */
    val isSynced: Boolean = false,
    
    /**
     * Last time (local monotonic) we successfully synced.
     */
    val lastSyncTime: Long = 0L
)
