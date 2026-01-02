package com.github.musicyou.sync.transport

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Firebase Firestore implementation of SignalingClient.
 * Uses Firestore documents to exchange SDP and ICE candidates.
 * 
 * Room structure:
 * /sync_rooms/{roomId}/
 *   - hostSdp: SessionDescription (offer)
 *   - participantSdp: SessionDescription (answer)
 *   - hostIce: Collection of ICE candidates from host
 *   - participantIce: Collection of ICE candidates from participant
 */
class FirebaseSignalingClient(
    private val isHost: Boolean
) : SignalingClient {
    
    companion object {
        private const val TAG = "MusicSync"
        private const val COLLECTION_ROOMS = "sync_rooms"
    }
    
    private val firestore = FirebaseFirestore.getInstance()
    private var roomId: String? = null
    private var sdpListener: ListenerRegistration? = null
    private var iceListener: ListenerRegistration? = null
    
    private val _incomingSdp = MutableSharedFlow<SessionDescription>(replay = 1)
    override val incomingSdp: SharedFlow<SessionDescription> = _incomingSdp.asSharedFlow()
    
    private val _incomingIce = MutableSharedFlow<IceCandidate>(replay = 10, extraBufferCapacity = 20)
    override val incomingIce: SharedFlow<IceCandidate> = _incomingIce.asSharedFlow()
    
    /**
     * Create a room (for host) or join a room (for participant).
     */
    suspend fun createOrJoinRoom(sessionCode: String) {
        roomId = sessionCode
        Log.d(TAG, "FirebaseSignaling: ${if (isHost) "Creating" else "Joining"} room $sessionCode")
        
        val roomRef = firestore.collection(COLLECTION_ROOMS).document(sessionCode)
        
        if (isHost) {
            // Host creates the room
            roomRef.set(mapOf(
                "createdAt" to System.currentTimeMillis(),
                "hostConnected" to true
            )).await()
            
            // Listen for participant's SDP answer
            sdpListener = roomRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "FirebaseSignaling: SDP listener error", error)
                    return@addSnapshotListener
                }
                
                snapshot?.getString("participantSdp")?.let { sdpString ->
                    snapshot.getString("participantSdpType")?.let { typeString ->
                        Log.i(TAG, "FirebaseSignaling: Received participant SDP")
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(typeString),
                            sdpString
                        )
                        _incomingSdp.tryEmit(sdp)
                    }
                }
            }
            
            // Listen for participant's ICE candidates
            iceListener = roomRef.collection("participantIce").addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                
                snapshot?.documentChanges?.forEach { change ->
                    val data = change.document.data
                    val candidate = IceCandidate(
                        data["sdpMid"] as? String ?: "",
                        (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0,
                        data["sdp"] as? String ?: ""
                    )
                    Log.d(TAG, "FirebaseSignaling: Received ICE from participant")
                    _incomingIce.tryEmit(candidate)
                }
            }
        } else {
            // Participant joins and listens for host's SDP offer
            sdpListener = roomRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "FirebaseSignaling: SDP listener error", error)
                    return@addSnapshotListener
                }
                
                snapshot?.getString("hostSdp")?.let { sdpString ->
                    snapshot.getString("hostSdpType")?.let { typeString ->
                        Log.i(TAG, "FirebaseSignaling: Received host SDP offer")
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(typeString),
                            sdpString
                        )
                        _incomingSdp.tryEmit(sdp)
                    }
                }
            }
            
            // Listen for host's ICE candidates
            iceListener = roomRef.collection("hostIce").addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                
                snapshot?.documentChanges?.forEach { change ->
                    val data = change.document.data
                    val candidate = IceCandidate(
                        data["sdpMid"] as? String ?: "",
                        (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0,
                        data["sdp"] as? String ?: ""
                    )
                    Log.d(TAG, "FirebaseSignaling: Received ICE from host")
                    _incomingIce.tryEmit(candidate)
                }
            }
            
            // Mark as joined
            try {
                roomRef.update("participantConnected", true).await()
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseSignaling: Failed to update participantConnected (room might be closed)", e)
            }
        }
    }
    
    override suspend fun sendSdp(sdp: SessionDescription) {
        val roomRef = firestore.collection(COLLECTION_ROOMS).document(roomId ?: return)
        
        val fieldPrefix = if (isHost) "host" else "participant"
        Log.i(TAG, "FirebaseSignaling: Sending SDP (${sdp.type})")
        
        try {
            roomRef.update(
                "${fieldPrefix}Sdp", sdp.description,
                "${fieldPrefix}SdpType", sdp.type.canonicalForm()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseSignaling: Failed to send SDP (room might be closed)", e)
        }
    }
    
    override suspend fun sendIce(candidate: IceCandidate) {
        val roomRef = firestore.collection(COLLECTION_ROOMS).document(roomId ?: return)
        
        val collectionName = if (isHost) "hostIce" else "participantIce"
        Log.d(TAG, "FirebaseSignaling: Sending ICE candidate")
        
        try {
            roomRef.collection(collectionName).add(mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )).await()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseSignaling: Failed to send ICE (room might be closed)", e)
        }
    }
    
    override suspend fun connect() {
        // Room connection is handled by createOrJoinRoom
    }
    
    override suspend fun disconnect() {
        Log.d(TAG, "FirebaseSignaling: Disconnecting")
        sdpListener?.remove()
        iceListener?.remove()
        
        // Optionally clean up room (host only)
        roomId?.let { id ->
            if (isHost) {
                try {
                    firestore.collection(COLLECTION_ROOMS).document(id).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "FirebaseSignaling: Failed to delete room", e)
                }
            }
        }
        
        roomId = null
    }
}
