package com.github.musicyou.sync.presence

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import com.github.musicyou.utils.shareOnlineStatusKey
import com.github.musicyou.utils.shareListeningStatusKey
import com.github.musicyou.utils.shareSessionInfoKey
import com.github.musicyou.utils.preferences

/**
 * Data class for current song information
 */
data class SongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String? = null,
    val startedAt: Long = System.currentTimeMillis()
)

/**
 * Data class for full user presence
 */
data class UserPresence(
    val online: Boolean = false,
    val lastSeen: Long = 0,
    val status: String = "idle", // "idle", "hosting", "participating"
    val sessionId: String? = null,
    val currentSong: SongInfo? = null
)

/**
 * PresenceManager: Single source of truth for RTDB presence & session membership.
 * 
 * Responsibilities:
 * - Mark user online/offline (auto-disconnect handling)
 * - Join/leave sessions (members map)
 * - Observe session members (StateFlow)
 * - Observe user presence (StateFlow)
 * 
 * Rules:
 * 1. RTDB decides who exists
 * 2. Disconnect ≠ bug — Firebase handles it
 * 3. Never touches playback logic
 */
object PresenceManager {
    private const val TAG = "PresenceManager"
    
    // App context for preferences (injected)
    private var appContext: Context? = null
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
    
    private fun getPrivacySettings(): Triple<Boolean, Boolean, Boolean> {
        val prefs = appContext?.preferences
        return Triple(
            prefs?.getBoolean(shareOnlineStatusKey, true) ?: true,
            prefs?.getBoolean(shareListeningStatusKey, true) ?: true,
            prefs?.getBoolean(shareSessionInfoKey, true) ?: true
        )
    }
    
    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance("https://musicyou-sync-default-rtdb.asia-southeast1.firebasedatabase.app").apply {
            Log.d(TAG, "Firebase RTDB instance initialized with regional URL")
        }
    }
    
    private val presenceRef: DatabaseReference by lazy { 
        database.getReference("presence")
    }
    
    private val sessionsRef: DatabaseReference by lazy { 
        database.getReference("sessions")
    }
    
    private var currentSessionId: String? = null
    private var currentUserRef: DatabaseReference? = null
    
    // State flows for observing data
    private val _currentSessionMembers = MutableStateFlow<Set<String>>(emptySet())
    val currentSessionMembers: StateFlow<Set<String>> = _currentSessionMembers.asStateFlow()
    
    /**
     * STEP 1: Connect and mark user online
     * Sets onDisconnect() to auto-clear presence
     */
    fun connect() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "connect: Cannot connect, user not authenticated")
            return
        }
        
        Log.i(TAG, "connect: Establishing presence for uid=$uid")
        
        val userPresenceRef = presenceRef.child(uid)
        currentUserRef = userPresenceRef
        
        // CRITICAL: Only write online and lastSeen to avoid race conditions
        // DO NOT touch status, sessionId, or currentSong here
        val presenceData = mapOf(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP
        )
        
        // Configure auto-disconnect - reset to offline state
        userPresenceRef.onDisconnect().updateChildren(
            mapOf(
                "online" to false,
                "lastSeen" to ServerValue.TIMESTAMP,
                "status" to "idle",
                "sessionId" to null,
                "currentSong" to null
            )
        ).addOnSuccessListener {
            Log.d(TAG, "connect: onDisconnect handler registered for uid=$uid")
        }.addOnFailureListener { e ->
            Log.e(TAG, "connect: Failed to register onDisconnect for uid=$uid", e)
        }
        
        // Use updateChildren instead of setValue to avoid overwriting existing fields
        userPresenceRef.updateChildren(presenceData)
            .addOnSuccessListener {
                Log.i(TAG, "connect: User presence set to ONLINE for uid=$uid")
                
                // TRIGGER POINT #1: Validate on cold start (after connect)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    validateSessionTruth()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "connect: Failed to set presence for uid=$uid", e)
            }
    }
    
    /**
     * CRITICAL INVARIANT ENFORCEMENT (NUCLEAR VERSION):
     * 
     * Session membership in /sessions/{sessionId}/members/{uid} is SINGLE SOURCE OF TRUTH.
     * This function is AUTHORITATIVE - it NEVER trusts cached state.
     * 
     * ALWAYS reads RTDB directly to verify truth.
     * If stale state detected → hard reset presence immediately.
     */
    suspend fun validateSessionTruth() {
        Log.e(TAG, "🔍 VALIDATION RUNNING") // Brutal visibility
        
        val uid = getCurrentUserUid()
        if (uid == null) {
            Log.w(TAG, "validateSessionTruth: No authenticated user")
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                // CRITICAL: Read RTDB directly - DO NOT trust cached currentSessionId
                val presenceSnapshot = presenceRef.child(uid).get().await()
                val rtdbSessionId = presenceSnapshot.child("sessionId").getValue(String::class.java)
                val rtdbStatus = presenceSnapshot.child("status").getValue(String::class.java)
                
                Log.e(TAG, "🔍 VALIDATE: rtdbSessionId=$rtdbSessionId, rtdbStatus=$rtdbStatus")
                
                if (rtdbSessionId == null) {
                    Log.d(TAG, "validateSessionTruth: No sessionId in RTDB, nothing to validate")
                    return@withContext
                }
                
                // Check if user is ACTUALLY in the session (TRUTH CHECK)
                val memberSnapshot = sessionsRef.child(rtdbSessionId)
                    .child("members").child(uid).get().await()
                
                if (!memberSnapshot.exists()) {
                    // 🧨 STALE STATE DETECTED - HARD RESET
                    Log.e(TAG, "🧨 STALE SESSION DETECTED! sessionId=$rtdbSessionId but member NOT in RTDB")
                    Log.e(TAG, "🧨 RESETTING PRESENCE TO IDLE")
                    
                    // Direct RTDB write - bypass updateStatus to avoid any logic
                    presenceRef.child(uid).updateChildren(
                        mapOf(
                            "status" to "idle",
                            "sessionId" to null,
                            "currentSong" to null
                        )
                    ).await()
                    
                    // Clear in-memory state
                    currentSessionId = null
                    _currentSessionMembers.value = emptySet()
                    
                    Log.i(TAG, "✅ Presence HARD RESET to idle complete")
                } else {
                    Log.d(TAG, "✅ Session $rtdbSessionId is VALID - member exists in RTDB")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ validateSessionTruth: Failed to validate", e)
            }
        }
    }
    
    /**
     * Disconnect and clean up
     */
    fun disconnect() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        Log.i(TAG, "disconnect: Cleaning up presence for uid=$uid")
        
        leaveSession()
        
        currentUserRef?.setValue(
            mapOf(
                "online" to false,
                "lastSeen" to ServerValue.TIMESTAMP
            )
        )
        
        currentUserRef = null
        Log.d(TAG, "disconnect: Presence cleared")
    }
    
    /**
     * STEP 2: Join a session
     * Adds uid to /sessions/{sessionId}/members/{uid}
     * Auto-remove on disconnect
     */
    fun joinSession(sessionId: String, isHost: Boolean = false) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "joinSession: Cannot join, user not authenticated")
            return
        }
        
        Log.i(TAG, "joinSession: Joining session=$sessionId as ${if (isHost) "HOST" else "PARTICIPANT"}, uid=$uid")
        
        // Leave previous session if any
        if (currentSessionId != null && currentSessionId != sessionId) {
            Log.d(TAG, "joinSession: Leaving previous session=$currentSessionId")
            leaveSession()
        }
        
        currentSessionId = sessionId
        val sessionRef = sessionsRef.child(sessionId)
        val memberRef = sessionRef.child("members").child(uid)
        
        // Configure auto-remove on disconnect
        memberRef.onDisconnect().removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "joinSession: onDisconnect member removal registered for session=$sessionId, uid=$uid")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "joinSession: Failed to register onDisconnect for session=$sessionId", e)
            }
        
        // Add member to session
        memberRef.setValue(true)
            .addOnSuccessListener {
                Log.i(TAG, "joinSession: Successfully joined session=$sessionId, uid=$uid")
                
                // CRITICAL: Only update status AFTER successful membership add
                // This enforces the invariant: presence.status can only be hosting/participating
                // if /sessions/{sessionId}/members/{uid} actually exists
                val status = if (isHost) "hosting" else "participating"
                updateStatus(status, sessionId)
                Log.d(TAG, "joinSession: Status updated to $status for session=$sessionId")
                
                // If host, set session metadata
                if (isHost) {
                    sessionRef.updateChildren(
                        mapOf(
                            "host" to uid,
                            "startedAt" to ServerValue.TIMESTAMP
                        )
                    ).addOnSuccessListener {
                        Log.d(TAG, "joinSession: Session metadata updated for host")
                    }
                }
                
                // TRIGGER POINT #2: Validate after join SUCCESS
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    validateSessionTruth()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "joinSession: Failed to join session=$sessionId", e)
                // DO NOT update status on failure - this maintains the invariant
                
                // TRIGGER POINT #2b: Validate after join FAILURE (paranoia)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    validateSessionTruth()
                }
            }
        
        // Start observing members
        observeSessionMembers(sessionId)
    }
    
    /**
     * Leave current session
     */
    fun leaveSession() {
        val uid = getCurrentUserUid()
        if (uid == null || currentSessionId == null) {
            Log.w(TAG, "leaveSession: Cannot leave - no user or no active session")
            return
        }
        
        Log.i(TAG, "leaveSession: Leaving session=$currentSessionId for uid=$uid")
        
        val sessionId = currentSessionId!!
        
        // Remove from RTDB session members
        sessionsRef.child(sessionId).child("members").child(uid).removeValue()
            .addOnSuccessListener {
                Log.i(TAG, "leaveSession: Successfully left session=$sessionId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "leaveSession: Failed to leave session=$sessionId", e)
            }
        
        currentSessionId = null
        _currentSessionMembers.value = emptySet()
        
        // Reset status to idle and clear sessionId
        updateStatus("idle", null)
        
        // TRIGGER POINT #3: Validate after leave
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            validateSessionTruth()
        }
    }
    
    /**
     * STEP 4: Observe session members (StateFlow)
     * Returns Set<String> of UIDs currently in the session
     */
    private fun observeSessionMembers(sessionId: String) {
        val membersRef = sessionsRef.child(sessionId).child("members")
        
        Log.d(TAG, "observeSessionMembers: Setting up listener for session=$sessionId")
        
        membersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentUserUid = getCurrentUserUid()
                val allMembers = snapshot.children.mapNotNull { it.key }.toSet()
                // Exclude self - only return other participants
                val members = if (currentUserUid != null) {
                    allMembers.filter { it != currentUserUid }.toSet()
                } else {
                    allMembers
                }
                Log.i(TAG, "observeSessionMembers: Members updated for session=$sessionId, count=${members.size}, members=$members (filtered self)")
                _currentSessionMembers.value = members
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeSessionMembers: Listener cancelled for session=$sessionId", error.toException())
            }
        })
    }
    
    /**
     * Observe a specific user's presence
     * Returns Flow<Boolean> (true = online, false = offline)
     */
    fun observePresence(uid: String): Flow<Boolean> = callbackFlow {
        Log.d(TAG, "observePresence: Setting up listener for uid=$uid")
        
        val userPresenceRef = presenceRef.child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val online = snapshot.child("online").getValue(Boolean::class.java) ?: false
                Log.d(TAG, "observePresence: Presence update for uid=$uid, online=$online")
                trySend(online)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observePresence: Listener cancelled for uid=$uid", error.toException())
                close(error.toException())
            }
        }
        
        userPresenceRef.addValueEventListener(listener)
        
        awaitClose {
            Log.d(TAG, "observePresence: Removing listener for uid=$uid")
            userPresenceRef.removeEventListener(listener)
        }
    }
    
    /**
     * Get current user's UID
     */
    fun getCurrentUserUid(): String? {
        return FirebaseAuth.getInstance().currentUser?.uid
    }
    
    /**
     * Update user status (idle/hosting/participating)
     * Respects privacy settings
     */
    fun updateStatus(status: String, sessionId: String? = null) {
        val uid = getCurrentUserUid()
        if (uid == null) {
            Log.w(TAG, "updateStatus: Cannot update, user not authenticated")
            return
        }
        
        val (shareOnline, _, shareSession) = getPrivacySettings()
        
        if (!shareOnline) {
            Log.d(TAG, "updateStatus: Skipped - privacy setting disabled")
            return
        }
        
        Log.i(TAG, "updateStatus: Updating status=$status, sessionId=$sessionId for uid=$uid")
        
        val updates = mutableMapOf<String, Any?>(
            "status" to status,
            "lastSeen" to ServerValue.TIMESTAMP
        )
        
        if (shareSession && sessionId != null) {
            updates["sessionId"] = sessionId
        } else {
            updates["sessionId"] = null
        }
        
        presenceRef.child(uid).updateChildren(updates)
            .addOnSuccessListener {
                Log.i(TAG, "updateStatus: Successfully updated status")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "updateStatus: Failed to update status", e)
            }
    }
    
    /**
     * Update currently playing song
     * Respects privacy settings
     */
    fun updateCurrentSong(
        songId: String?,
        title: String?,
        artist: String?,
        albumArt: String? = null
    ) {
        val uid = getCurrentUserUid()
        if (uid == null || songId == null || title == null || artist == null) {
            Log.w(TAG, "updateCurrentSong: Missing required data")
            return
        }
        
        val (shareOnline, shareListen, _) = getPrivacySettings()
        
        if (!shareOnline || !shareListen) {
            Log.d(TAG, "updateCurrentSong: Skipped - privacy setting disabled")
            return
        }
        
        Log.i(TAG, "updateCurrentSong: Updating song=$title for uid=$uid")
        
        val songData = mapOf(
            "id" to songId,
            "title" to title,
            "artist" to artist,
            "albumArt" to (albumArt ?: ""),
            "startedAt" to ServerValue.TIMESTAMP
        )
        
        presenceRef.child(uid).child("currentSong").setValue(songData)
            .addOnSuccessListener {
                Log.i(TAG, "updateCurrentSong: Successfully updated current song")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "updateCurrentSong: Failed to update current song", e)
            }
    }
    
    /**
     * Clear currently playing song
     */
    fun clearCurrentSong() {
        val uid = getCurrentUserUid()
        if (uid == null) return
        
        Log.d(TAG, "clearCurrentSong: Clearing current song for uid=$uid")
        
        presenceRef.child(uid).child("currentSong").removeValue()
    }
    
    /**
     * Observe full presence data for a user (not just online/offline)
     */
    fun observeFullPresence(uid: String): Flow<UserPresence> = callbackFlow {
        Log.d(TAG, "observeFullPresence: Setting up listener for uid=$uid")
        
        val userPresenceRef = presenceRef.child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val online = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0
                val status = snapshot.child("status").getValue(String::class.java) ?: "idle"
                val sessionId = snapshot.child("sessionId").getValue(String::class.java)
                
                val currentSong = snapshot.child("currentSong").let { songSnap ->
                    if (songSnap.exists()) {
                        SongInfo(
                            id = songSnap.child("id").getValue(String::class.java) ?: "",
                            title = songSnap.child("title").getValue(String::class.java) ?: "",
                            artist = songSnap.child("artist").getValue(String::class.java) ?: "",
                            albumArt = songSnap.child("albumArt").getValue(String::class.java),
                            startedAt = songSnap.child("startedAt").getValue(Long::class.java) ?: 0
                        )
                    } else null
                }
                
                val presence = UserPresence(
                    online = online,
                    lastSeen = lastSeen,
                    status = status,
                    sessionId = sessionId,
                    currentSong = currentSong
                )
                
                Log.d(TAG, "observeFullPresence: Presence update for uid=$uid, status=$status, song=${currentSong?.title}")
                trySend(presence)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeFullPresence: Listener cancelled for uid=$uid", error.toException())
                close(error.toException())
            }
        }
        
        userPresenceRef.addValueEventListener(listener)
        
        awaitClose {
            Log.d(TAG, "observeFullPresence: Removing listener for uid=$uid")
            userPresenceRef.removeEventListener(listener)
        }
    }
}
