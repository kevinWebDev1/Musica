package com.github.musicyou.sync.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for the underlying network transport (WebRTC, Bluetooth, etc.).
 * Decouples logic from the specific transport implementation.
 */
interface TransportLayer {

    /**
     * Current state of the connection.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Represents an incoming message with its sender's unique ID.
     */
    data class TransportMessage(
        val senderId: String,
        val data: ByteArray
    )

    /**
     * Stream of incoming messages.
     */
    val incomingMessages: SharedFlow<TransportMessage>

    /**
     * The active Session ID (if any).
     */
    val sessionId: StateFlow<String?>

    /**
     * List of connected peer IDs/Names.
     */
    val connectedPeers: StateFlow<List<String>>

    /**
     * Connects to the session/peer.
     * @param sessionId The session ID to join, or null to host/discovery.
     */
    suspend fun connect(sessionId: String? = null)

    /**
     * Disconnects from the session/peer.
     */
    suspend fun disconnect()

    /**
     * Sends data to the connected peer(s).
     * @param data The raw byte array to send.
     */
    suspend fun send(data: ByteArray)

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
