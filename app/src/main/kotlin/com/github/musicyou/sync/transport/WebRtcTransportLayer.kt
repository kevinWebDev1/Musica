package com.github.musicyou.sync.transport

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WebRTC implementation of TransportLayer for long-distance sync.
 * Uses DataChannels for low-latency communication.
 * Uses FirebaseSignalingClient to exchange SDP/ICE.
 */
class WebRtcTransportLayer(
    private val context: Context,
    private val isHost: Boolean
) : TransportLayer {
    
    companion object {
        private const val TAG = "MusicSync"
        private const val DATA_CHANNEL_LABEL = "syncChannel"
        private const val CONNECTION_TIMEOUT_MS = 30000L  // 30 second timeout
    }

    private val _connectionState = MutableStateFlow(TransportLayer.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportLayer.ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<TransportLayer.TransportMessage>(replay = 1, extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<TransportLayer.TransportMessage> = _incomingMessages.asSharedFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    override val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var signalingClient: FirebaseSignalingClient? = null
    
    // --- Edge case guards ---
    private var hasReceivedRemoteSdp = false
    private var isRemoteDescriptionSet = false
    private val pendingIceCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var connectionTimeoutJob: Job? = null
    private var isDisconnecting = false

    private fun initFactory() {
        if (peerConnectionFactory != null) return
        
        Log.d(TAG, "WebRTC: Initializing PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    override suspend fun connect(sessionId: String?) {
        if (isDisconnecting) {
            Log.w(TAG, "WebRTC: Ignoring connect() while disconnecting")
            return
        }
        
        // Cleanup previous connection if restarting
        try {
            dataChannel?.close()
            peerConnection?.close()
            signalingClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC: Error clearing previous connection: ${e.message}")
        }
        
        initFactory()
        _connectionState.value = TransportLayer.ConnectionState.CONNECTING
        
        // Reset state for fresh connection
        hasReceivedRemoteSdp = false
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()
        
        // Generate or use session ID
        val roomCode = sessionId ?: java.util.UUID.randomUUID().toString().take(6).uppercase()
        _sessionId.value = roomCode
        Log.i(TAG, "WebRTC: ${if (isHost) "Hosting" else "Joining"} room $roomCode")
        
        // Create signaling client
        signalingClient = FirebaseSignalingClient(isHost = isHost)
        
        // Create peer connection with STUN servers
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, createPeerConnectionObserver())
        
        if (peerConnection == null) {
            Log.e(TAG, "WebRTC: Failed to create PeerConnection")
            _connectionState.value = TransportLayer.ConnectionState.ERROR
            return
        }
        
        // Connect to Firebase signaling room
        signalingClient?.createOrJoinRoom(roomCode)
        
        // Listen for incoming SDP (with deduplication guard)
        signalingClient?.incomingSdp?.onEach { remoteSdp ->
            if (!hasReceivedRemoteSdp && !isDisconnecting) {
                Log.i(TAG, "WebRTC: Received remote SDP type=${remoteSdp.type}")
                hasReceivedRemoteSdp = true
                handleRemoteSdp(remoteSdp)
            } else {
                Log.d(TAG, "WebRTC: Ignoring duplicate SDP (already processed or disconnecting)")
            }
        }?.launchIn(scope)
        
        // Listen for incoming ICE candidates
        signalingClient?.incomingIce?.onEach { candidate ->
            addIceCandidate(candidate)
        }?.launchIn(scope)
        
        if (isHost) {
            // Host creates data channel and offer
            createDataChannel()
            createOffer()
        }
        // Participant waits for host's offer via signaling
        
        // Start connection timeout
        startConnectionTimeout()
    }
    
    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_connectionState.value == TransportLayer.ConnectionState.CONNECTING) {
                Log.w(TAG, "WebRTC: Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
                _connectionState.value = TransportLayer.ConnectionState.ERROR
            }
        }
    }
    
    private fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }
    
    /**
     * Add ICE candidate, queuing if remote description not yet set.
     */
    private fun addIceCandidate(candidate: IceCandidate) {
        if (isDisconnecting) return
        
        if (isRemoteDescriptionSet) {
            Log.d(TAG, "WebRTC: Adding ICE candidate immediately")
            peerConnection?.addIceCandidate(candidate)
        } else {
            Log.d(TAG, "WebRTC: Queuing ICE candidate (remote desc not set yet)")
            pendingIceCandidates.add(candidate)
        }
    }
    
    /**
     * Drain queued ICE candidates after remote description is set.
     */
    private fun drainPendingIceCandidates() {
        if (pendingIceCandidates.isNotEmpty()) {
            Log.d(TAG, "WebRTC: Draining ${pendingIceCandidates.size} pending ICE candidates")
            pendingIceCandidates.forEach { candidate ->
                peerConnection?.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()
        }
    }
    
    private fun createDataChannel() {
        Log.d(TAG, "WebRTC: Creating data channel")
        val init = DataChannel.Init().apply {
            ordered = true
        }
        dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, init)
        setupDataChannel(dataChannel)
    }
    
    private fun createOffer() {
        val pc = peerConnection
        if (pc == null) {
            Log.e(TAG, "WebRTC: Cannot create offer - peerConnection is null")
            return
        }
        
        Log.d(TAG, "WebRTC: Creating offer")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.i(TAG, "WebRTC: Offer created, setting local description")
                    pc.setLocalDescription(createSetSdpObserver("local offer"), it)
                    scope.launch {
                        signalingClient?.sendSdp(it)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "WebRTC: Failed to create offer: $error")
                _connectionState.value = TransportLayer.ConnectionState.ERROR
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    private fun createAnswer() {
        val pc = peerConnection
        if (pc == null) {
            Log.e(TAG, "WebRTC: Cannot create answer - peerConnection is null")
            return
        }
        
        Log.d(TAG, "WebRTC: Creating answer")
        val constraints = MediaConstraints()
        
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.i(TAG, "WebRTC: Answer created, setting local description")
                    pc.setLocalDescription(createSetSdpObserver("local answer"), it)
                    scope.launch {
                        signalingClient?.sendSdp(it)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "WebRTC: Failed to create answer: $error")
                _connectionState.value = TransportLayer.ConnectionState.ERROR
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    private fun handleRemoteSdp(remoteSdp: SessionDescription) {
        val pc = peerConnection
        if (pc == null) {
            Log.e(TAG, "WebRTC: Cannot set remote SDP - peerConnection is null")
            return
        }
        
        Log.i(TAG, "WebRTC: Setting remote description type=${remoteSdp.type}")
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "WebRTC: Remote description set successfully")
                isRemoteDescriptionSet = true
                
                // Drain any ICE candidates that arrived before remote desc was set
                drainPendingIceCandidates()
                
                if (remoteSdp.type == SessionDescription.Type.OFFER) {
                    // Participant received offer, create answer
                    createAnswer()
                }
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "WebRTC: Failed to set remote description: $error")
                _connectionState.value = TransportLayer.ConnectionState.ERROR
            }
        }, remoteSdp)
    }
    
    private fun createSetSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {
            Log.d(TAG, "WebRTC: Set $tag success")
        }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "WebRTC: Failed to set $tag: $error")
        }
    }
    
    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "WebRTC: Signaling state: $state")
        }
        
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "WebRTC: ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    cancelConnectionTimeout()
                    _connectionState.value = TransportLayer.ConnectionState.CONNECTED
                    _connectedPeers.value = listOf("peer")
                    Log.i(TAG, "WebRTC: Connected!")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "WebRTC: ICE disconnected - attempting to recover or reconnect...")
                    // If stuck > 5s, reconnect
                    scope.launch {
                        delay(5000)
                        if (peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                             Log.e(TAG, "WebRTC: ICE stuck in DISCONNECTED. Forcing reconnect.")
                             val currentId = _sessionId.value
                             if (currentId != null && !isDisconnecting) {
                                 connect(currentId)
                             }
                        }
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "WebRTC: ICE connection failed. Initiating Auto-Reconnect...")
                    val currentId = _sessionId.value
                    if (currentId != null && !isDisconnecting) {
                        _connectionState.value = TransportLayer.ConnectionState.CONNECTING
                        scope.launch {
                            delay(1000) // Brief backoff
                            connect(currentId)
                        }
                    } else {
                         _connectionState.value = TransportLayer.ConnectionState.ERROR
                         _connectedPeers.value = emptyList()
                         _sessionId.value = null
                    }
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    Log.i(TAG, "WebRTC: ICE connection closed")
                    _connectionState.value = TransportLayer.ConnectionState.DISCONNECTED
                    _connectedPeers.value = emptyList()
                    _sessionId.value = null  // CRITICAL: Signal disconnect to SessionManager
                }
                else -> {}
            }
        }
        
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "WebRTC: ICE gathering state: $state")
        }
        
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d(TAG, "WebRTC: Sending local ICE candidate")
                scope.launch {
                    signalingClient?.sendIce(it)
                }
            }
        }
        
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        
        override fun onAddStream(stream: MediaStream?) {}
        
        override fun onRemoveStream(stream: MediaStream?) {}
        
        override fun onDataChannel(dc: DataChannel?) {
            Log.i(TAG, "WebRTC: Remote data channel opened")
            dataChannel = dc
            setupDataChannel(dc)
        }
        
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "WebRTC: Renegotiation needed")
        }
        
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private fun setupDataChannel(dc: DataChannel?) {
        dc?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            
            override fun onStateChange() {
                Log.i(TAG, "WebRTC: Data channel state: ${dc.state()}")
                when (dc.state()) {
                    DataChannel.State.OPEN -> {
                        cancelConnectionTimeout()
                        _connectionState.value = TransportLayer.ConnectionState.CONNECTED
                        Log.i(TAG, "WebRTC: Data channel OPEN - ready to sync!")
                    }
                    DataChannel.State.CLOSED -> {
                        Log.d(TAG, "WebRTC: Data channel closed")
                    }
                    else -> {}
                }
            }
            
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    Log.d(TAG, "WebRTC: Received ${data.size} bytes from peer")
                    scope.launch {
                        _incomingMessages.emit(TransportLayer.TransportMessage("webrtc-peer", data))
                    }
                }
            }
        })
    }

    override suspend fun disconnect() {
        if (isDisconnecting) {
            Log.d(TAG, "WebRTC: Already disconnecting")
            return
        }
        
        isDisconnecting = true
        Log.d(TAG, "WebRTC: Disconnecting")
        
        cancelConnectionTimeout()
        
        try {
            dataChannel?.close()
            peerConnection?.close()
            signalingClient?.disconnect()
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC: Error during disconnect", e)
        }
        
        dataChannel = null
        peerConnection = null
        signalingClient = null
        peerConnectionFactory = null
        hasReceivedRemoteSdp = false
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()
        isDisconnecting = false
        
        _connectionState.value = TransportLayer.ConnectionState.DISCONNECTED
        _connectedPeers.value = emptyList()
    }

    override suspend fun send(data: ByteArray) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "WebRTC: sendPayload: Channel not ready (null or not OPEN)")
            return
        }
        
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
        val success = dc.send(buffer)
        if (success) {
            Log.v(TAG, "WebRTC: sendPayload: Success")
        } else {
            Log.w(TAG, "WebRTC: sendPayload: Failed")
        }
    }
}
