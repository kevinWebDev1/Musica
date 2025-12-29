package com.github.musicyou.sync.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Composite TransportLayer that manages multiple underlying transports.
 * Strategy: Redundant Broadcast (Simulcast).
 * - Sends to ALL connected transports.
 * - Receives from ALL transports.
 * - Connection State is CONNECTED if at least one child is CONNECTED.
 */
class TransportManager(
    private val transports: List<TransportLayer>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default) // IO or Default for flow processing
) : TransportLayer {

    private val _connectionState = MutableStateFlow(TransportLayer.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportLayer.ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ByteArray>()
    override val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    override val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    init {
        // Aggregate connection states
        transports.forEach { transport ->
            transport.connectionState.onEach { updateAggregatedState() }.launchIn(scope)
            transport.sessionId.onEach { updateAggregatedSessionId() }.launchIn(scope)
            transport.connectedPeers.onEach { updateAggregatedPeers() }.launchIn(scope)
        }

        // Aggregate incoming messages
        val flows = transports.map { it.incomingMessages }
        merge(*flows.toTypedArray())
            .onEach { data ->
                _incomingMessages.emit(data)
            }
            .launchIn(scope)
    }

    private fun updateAggregatedSessionId() {
        // Take the first non-null session ID
        _sessionId.value = transports.firstNotNullOfOrNull { it.sessionId.value }
    }

    private fun updateAggregatedPeers() {
        // Union of all connected peers
        _connectedPeers.value = transports.flatMap { it.connectedPeers.value }.distinct()
    }

    private fun updateAggregatedState() {
        // If ANY transport is CONNECTED, we are CONNECTED.
        // If NONE are CONNECTED but ANY is CONNECTING, we are CONNECTING.
        // Otherwise DISCONNECTED (or ERROR if all error? simpler to just say disconnected).
        
        val anyConnected = transports.any { it.connectionState.value == TransportLayer.ConnectionState.CONNECTED }
        val anyConnecting = transports.any { it.connectionState.value == TransportLayer.ConnectionState.CONNECTING }

        val newState = when {
            anyConnected -> TransportLayer.ConnectionState.CONNECTED
            anyConnecting -> TransportLayer.ConnectionState.CONNECTING
            else -> TransportLayer.ConnectionState.DISCONNECTED
        }

        if (_connectionState.value != newState) {
            _connectionState.value = newState
        }
    }

    override suspend fun connect(sessionId: String?) {
        transports.forEach { it.connect(sessionId) }
    }

    override suspend fun disconnect() {
        transports.forEach { it.disconnect() }
    }

    override suspend fun send(data: ByteArray) {
        // Broadcast to all CONNECTED transports
        transports.forEach { transport ->
            if (transport.connectionState.value == TransportLayer.ConnectionState.CONNECTED) {
                transport.send(data)
            }
        }
    }
}
