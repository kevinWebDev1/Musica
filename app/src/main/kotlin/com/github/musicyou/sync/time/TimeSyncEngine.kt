package com.github.musicyou.sync.time

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Calculates global time offset using NTP algorithm with multi-sample averaging.
 * 
 * Improvements over simple single-sample:
 * 1. Stores last N samples for robustness
 * 2. Uses MEDIAN to reject outliers (network jitter)
 * 3. Applies Exponential Moving Average for smoothing
 * 4. Logs offset stability for debugging
 */
open class TimeSyncEngine {

    companion object {
        private const val TAG = "TimeSyncEngine"
        private const val MAX_SAMPLES = 5           // Keep last 5 samples
        private const val EMA_ALPHA = 0.3f          // Exponential Moving Average alpha (0.3 = recent values weighted more)
        private const val MAX_VALID_RTT_MS = 2000L  // Ignore samples with RTT > 2s (likely invalid)
    }

    private val _clockState = MutableStateFlow(ClockState())
    val clockState: StateFlow<ClockState> = _clockState.asStateFlow()
    
    // Store recent offset samples for median calculation
    private val offsetSamples = mutableListOf<Long>()
    
    // Smoothed offset using EMA
    private var smoothedOffset: Long? = null

    /**
     * Returns the estimated global time based on current local time.
     * Uses smoothed offset if available, otherwise raw offset.
     */
    open fun getGlobalTime(): Long {
        val offset = smoothedOffset ?: _clockState.value.offset
        return System.currentTimeMillis() + offset
    }

    /**
     * Processes a PONG response to calculate offset.
     * NTP Formula:
     * offset = ((t1 - t0) + (t2 - t3)) / 2
     *
     * Applies multi-sample averaging for robustness:
     * 1. Validate RTT
     * 2. Add to sample buffer
     * 3. Calculate median of samples
     * 4. Apply exponential moving average
     *
     * @param t0 Local time when PING was sent
     * @param t1 Host time when PING was received
     * @param t2 Host time when PONG was sent
     * @param t3 Local time when PONG was received
     */
    fun processPong(t0: Long, t1: Long, t2: Long, t3: Long) {
        val rtt = (t3 - t0) - (t2 - t1)
        val rawOffset = ((t1 - t0) + (t2 - t3)) / 2

        // Validity check: ignore massive jumps or negative RTTs
        if (rtt < 0 || rtt > MAX_VALID_RTT_MS) {
            Log.w(TAG, "processPong: REJECTED sample (rtt=${rtt}ms, threshold=${MAX_VALID_RTT_MS}ms)")
            return
        }
        
        // Add to sample buffer (keep last MAX_SAMPLES)
        synchronized(offsetSamples) {
            offsetSamples.add(rawOffset)
            if (offsetSamples.size > MAX_SAMPLES) {
                offsetSamples.removeAt(0)
            }
        }
        
        // Calculate median of samples (robust against outliers)
        val medianOffset = calculateMedian(offsetSamples)
        
        // Apply Exponential Moving Average for smoothing
        smoothedOffset = if (smoothedOffset == null) {
            medianOffset
        } else {
            ((1 - EMA_ALPHA) * smoothedOffset!! + EMA_ALPHA * medianOffset).toLong()
        }
        
        Log.d(TAG, "processPong: rtt=${rtt}ms, rawOffset=${rawOffset}ms, median=${medianOffset}ms, smoothed=${smoothedOffset}ms, samples=${offsetSamples.size}")
        
        _clockState.update { current ->
            ClockState(
                offset = smoothedOffset ?: medianOffset,
                rtt = rtt,
                isSynced = offsetSamples.size >= 2, // Consider synced after 2+ samples
                lastSyncTime = System.nanoTime()
            )
        }
    }
    
    /**
     * Calculate median of a list of values.
     * Median is robust against outliers (unlike mean).
     */
    private fun calculateMedian(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }

    fun reset() {
        offsetSamples.clear()
        smoothedOffset = null
        _clockState.value = ClockState()
        Log.i(TAG, "reset: Clock state cleared")
    }
}

