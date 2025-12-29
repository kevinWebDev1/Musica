package com.github.musicyou.sync.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TransportLayer implementation using Google Nearby Connections API.
 * Uses Strategy.P2P_STAR for Host (Advertiser) -> Participant (Discoverer) topology.
 * Strictly "dumb" pipe: does not interpret payload content.
 */
class NearbyTransportLayer(
    private val context: Context,
    private val serviceId: String = "com.github.musicyou.sync", // Must match manifest
    private val isHost: Boolean
) : TransportLayer {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    
    private val _connectionState = MutableStateFlow(TransportLayer.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportLayer.ConnectionState> = _connectionState.asStateFlow()

    // Buffer of 64 to prevent message drops
    private val _incomingMessages = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO)

    private var connectedEndpointId: String? = null

    private val TAG = "MusicSync"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            android.util.Log.d(TAG, "onPayloadReceived: endpoint=$endpointId type=${payload.type}")
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    android.util.Log.i(TAG, "onPayloadReceived: Received ${bytes.size} bytes, emitting to flow")
                    scope.launch {
                        _incomingMessages.emit(bytes)
                        android.util.Log.d(TAG, "onPayloadReceived: Emitted to incomingMessages flow")
                    }
                } ?: run {
                    android.util.Log.w(TAG, "onPayloadReceived: payload.asBytes() was null")
                }
            } else {
                android.util.Log.w(TAG, "onPayloadReceived: Ignoring non-BYTES payload type=${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can track progress here for large files if needed
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            android.util.Log.d(TAG, "onConnectionInitiated: endpoint=$endpointId name=${info.endpointName} incoming=${info.isIncomingConnection}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener { android.util.Log.d(TAG, "acceptConnection: Success for $endpointId") }
                .addOnFailureListener { e -> android.util.Log.e(TAG, "acceptConnection: Failed for $endpointId", e) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            android.util.Log.d(TAG, "onConnectionResult: endpoint=$endpointId status=${result.status.statusCode}")
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpointIds.add(endpointId)
                    _connectedPeers.value = connectedEndpointIds.toList()
                    android.util.Log.i(TAG, "Connected to: $endpointId. Total peers: ${connectedEndpointIds.size}")
                    
                    // We are connected if we have at least one peer? Or just state transition.
                    _connectionState.value = TransportLayer.ConnectionState.CONNECTED
                }
                else -> {
                    android.util.Log.e(TAG, "Connection failed to $endpointId: ${result.status.statusCode}")
                    if (connectedEndpointIds.isEmpty()) {
                        _connectionState.value = TransportLayer.ConnectionState.ERROR
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            android.util.Log.d(TAG, "onDisconnected: endpoint=$endpointId")
            connectedEndpointIds.remove(endpointId)
            _connectedPeers.value = connectedEndpointIds.toList()
            
            if (connectedEndpointIds.isEmpty()) {
                _connectionState.value = TransportLayer.ConnectionState.DISCONNECTED
            }
        }
    }

    private val _sessionId = MutableStateFlow<String?>(null)
    override val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()

    private val connectedEndpointIds = mutableSetOf<String>()

    override suspend fun connect(sessionId: String?) {
        android.util.Log.d(TAG, "connect: requesting sessionId=$sessionId")
        
        // Stop any existing advertising/discovery first to prevent STATUS_ALREADY_ADVERTISING
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        
        _connectionState.value = TransportLayer.ConnectionState.CONNECTING
        
        // Dynamic Role Switching: Null ID = Host, Non-Null ID = Participant
        // We override the constructor 'isHost' if specific intention is passed
        // Or we interpret sessionId as the target to join.
        
        if (sessionId == null) {
            // Hosting
            // Generate a random session ID if we are hosting
            _sessionId.value = java.util.UUID.randomUUID().toString().take(6).uppercase()
            startAdvertising()
        } else {
            // Joining
            _sessionId.value = sessionId
            startDiscovery()
        }
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()
        
        val name = "MusicYou Host ${_sessionId.value}"
        android.util.Log.d(TAG, "startAdvertising: name=$name serviceId=$serviceId")

        connectionsClient.startAdvertising(
            name, // Broadcast Session ID in name
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            android.util.Log.d(TAG, "startAdvertising: Success")
        }.addOnFailureListener { e ->
            android.util.Log.e(TAG, "startAdvertising: Failed", e)
            _connectionState.value = TransportLayer.ConnectionState.ERROR
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()
            
        val targetSessionId = _sessionId.value ?: return

        android.util.Log.d(TAG, "startDiscovery: targetSessionId=$targetSessionId serviceId=$serviceId")

        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    val name = info.endpointName
                    android.util.Log.d(TAG, "onEndpointFound: id=$endpointId name=$name")
                    
                    // Check if this endpoint matches our target Session ID?
                    // Name format: "MusicYou Host <CODE>"
                    if (name.contains(targetSessionId, ignoreCase = true)) {
                        android.util.Log.i(TAG, "Match found! Requesting connection to $endpointId")
                        // Request connection automatically if code matches
                        connectionsClient.requestConnection(
                            "Participant", // Our local name
                            endpointId,
                            connectionLifecycleCallback
                        ).addOnSuccessListener {
                            android.util.Log.d(TAG, "requestConnection: Success")
                        }.addOnFailureListener { e ->
                            // Log failure
                            android.util.Log.e(TAG, "requestConnection: Failed", e)
                            _connectionState.value = TransportLayer.ConnectionState.ERROR
                        }
                    } else {
                         android.util.Log.d(TAG, "Ignored endpoint $name (doesn't contain $targetSessionId)")
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    android.util.Log.d(TAG, "onEndpointLost: $endpointId")
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            android.util.Log.d(TAG, "startDiscovery: Success")
        }.addOnFailureListener { e ->
            android.util.Log.e(TAG, "startDiscovery: Failed", e)
            _connectionState.value = TransportLayer.ConnectionState.ERROR
        }
    }

    override suspend fun disconnect() {
        android.util.Log.d(TAG, "disconnect")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpointIds.clear()
        _connectedPeers.value = emptyList()
        _sessionId.value = null
        _connectionState.value = TransportLayer.ConnectionState.DISCONNECTED
    }

    override suspend fun send(data: ByteArray) {
        // android.util.Log.v(TAG, "send: ${data.size} bytes to ${connectedEndpointIds.size} peers")
        val payload = Payload.fromBytes(data)
        if (connectedEndpointIds.isNotEmpty()) {
            connectionsClient.sendPayload(connectedEndpointIds.toList(), payload)
                .addOnSuccessListener {
                    android.util.Log.v(TAG, "sendPayload: Success")
                }
                .addOnFailureListener { e ->
                     android.util.Log.e(TAG, "sendPayload: Failed", e)
                }
        }
    }
}
