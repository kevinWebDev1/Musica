package com.github.musicyou.sync.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A dummy transport layer for testing and validation.
 * acts as a loopback or controllable mock.
 */
class MockTransportLayer : TransportLayer {

    private val _connectionState = MutableStateFlow(TransportLayer.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportLayer.ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ByteArray>()
    override val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    override val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    override suspend fun connect(sessionId: String?) {
        _connectionState.value = TransportLayer.ConnectionState.CONNECTING
        // Simulate connection delay
        if (sessionId != null) _sessionId.value = sessionId
        _connectionState.value = TransportLayer.ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        _connectionState.value = TransportLayer.ConnectionState.DISCONNECTED
    }

    val sentMessages = mutableListOf<ByteArray>()

    override suspend fun send(data: ByteArray) {
        sentMessages.add(data)
        // In a real mock, we might route this to another MockTransportLayer instance.
        // For simple debugging, we can verify this was called.
        println("MockTransportLayer sent: ${data.size} bytes")
    }

    /**
     * Helper method to simulate receiving a message from the network.
     */
    suspend fun simulateIncomingMessage(data: ByteArray) {
        _incomingMessages.emit(data)
    }
    
    fun setConnected(connected: Boolean) {
        _connectionState.value = if (connected) TransportLayer.ConnectionState.CONNECTED else TransportLayer.ConnectionState.DISCONNECTED
    }
}
