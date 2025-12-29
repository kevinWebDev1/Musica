package com.github.musicyou.sync.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Calculates global time offset using NTP algorithm.
 * Strictly reactive: receives PONGs, updates State.
 * Does NOT send messages directly (SessionManager must poll or be triggered).
 */
open class TimeSyncEngine {

    private val _clockState = MutableStateFlow(ClockState())
    val clockState: StateFlow<ClockState> = _clockState.asStateFlow()

    /**
     * Returns the estimated global time based on current local time.
     */
    open fun getGlobalTime(): Long {
        return System.currentTimeMillis() + _clockState.value.offset
    }

    /**
     * Processes a PONG response to calculate offset.
     * NTP Formula:
     * offset = ((t1 - t0) + (t2 - t3)) / 2
     *
     * @param t0 Local time when PING was sent
     * @param t1 Host time when PING was received
     * @param t2 Host time when PONG was sent
     * @param t3 Local time when PONG was received
     */
    fun processPong(t0: Long, t1: Long, t2: Long, t3: Long) {
        val rtt = (t3 - t0) - (t2 - t1)
        val offset = ((t1 - t0) + (t2 - t3)) / 2

        // Simple validity check: ignore massive jumps or negative RTTs
        if (rtt < 0) return 
        
        // TODO: Implement sophisticated smoothing/filtering (e.g. Marzullo's algorithm or simple exponential moving average)
        // For now, we just take the latest valid reading to establish baseline.
        
        _clockState.update { current ->
            ClockState(
                offset = offset,
                rtt = rtt,
                isSynced = true,
                lastSyncTime = System.nanoTime()
            )
        }
    }

    fun reset() {
        _clockState.value = ClockState()
    }
}
