package com.github.musicyou.sync.transport

import kotlinx.coroutines.flow.SharedFlow
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Interface for the signaling channel required to bootstrap WebRTC.
 * Implementations might use WebSocket, HTTP, or Firebase.
 */
interface SignalingClient {
    
    val incomingSdp: SharedFlow<SessionDescription>
    val incomingIce: SharedFlow<IceCandidate>
    
    suspend fun sendSdp(sdp: SessionDescription)
    suspend fun sendIce(candidate: IceCandidate)
    
    suspend fun connect()
    suspend fun disconnect()
}
