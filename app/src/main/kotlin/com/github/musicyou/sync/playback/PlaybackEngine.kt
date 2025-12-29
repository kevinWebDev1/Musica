package com.github.musicyou.sync.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * strict imperative playback engine interface.
 * NO global timestamps. NO scheduling.
 * All commands are immediate.
 */
interface PlaybackEngine {

    /**
     * Observable stream of playback state changes.
     */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Prepares the engine for playback.
     */
    fun prepare()

    /**
     * Loads a track by its media ID.
     * @param mediaId The unique identifier of the track.
     * @param seekPositionMs Optional position to seek to after track loads (default 0).
     * @param autoPlay Whether to start playback automatically after loading (default false).
     */
    fun loadTrack(mediaId: String, seekPositionMs: Long = 0, autoPlay: Boolean = false)

    /**
     * Starts playback immediately.
     */
    fun play()

    /**
     * Pauses playback immediately.
     */
    fun pause()

    /**
     * Seeks to the specified position immediately.
     * @param positionMs Position in milliseconds from the start of the track.
     */
    fun seekTo(positionMs: Long)

    /**
     * Sets the volume of the player.
     * @param volume 0.0f to 1.0f
     */
    fun setVolume(volume: Float)

    /**
     * Sets the playback speed.
     * @param speed 1.0f is normal speed.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Releases resources.
     */
    fun release()
}
