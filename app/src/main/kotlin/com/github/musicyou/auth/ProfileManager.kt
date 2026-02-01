package com.github.musicyou.auth

import android.util.Log
import android.content.Context
import androidx.core.content.edit
import com.github.musicyou.utils.displayNameKey
import com.github.musicyou.utils.preferences
import com.github.musicyou.utils.profileImageUrlKey
import com.github.musicyou.utils.usernameKey
import com.github.musicyou.utils.onboardedKey
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import com.github.musicyou.utils.profileImageLastUpdatedKey
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Represents a friend's presence/activity status.
 */
data class FriendPresence(
    val uid: String,
    val status: String, // "hosting", "participating", "idle"
    val sessionId: String? = null,
    val lastUpdated: Long = 0L
)

/**
 * Manages user profile data, username registration, and Friend Requests.
 */
object ProfileManager {
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private const val USERS_COLLECTION = "users"
    private const val USERNAMES_COLLECTION = "usernames"
    
    private val reservedUsernames = setOf(
        "admin", "support", "system", "musicyou", "moderator", 
        "official", "google", "firebase", "anonymous", "root"
    )

    /**
     * Validates a username against app-level constraints.
     */
    fun validateUsername(username: String): String? {
        val trimmed = username.trim()
        if (trimmed.length < 3) return "Username is too short (min 3)"
        if (trimmed.length > 20) return "Username is too long (max 20)"
        if (!trimmed[0].isLetter()) return "Must start with a letter"
        if (trimmed.contains(" ")) return "No spaces allowed"
        val validChars = trimmed.all { it.isLetter() || it.isDigit() || it == '_' || it == '.' }
        if (!validChars) return "Only letters, numbers, _ and . allowed"
        if (trimmed.startsWith("_") || trimmed.startsWith(".") || trimmed.endsWith("_") || trimmed.endsWith(".")) {
            return "Cannot start or end with _ or ."
        }
        if (trimmed.contains("__") || trimmed.contains("..") || trimmed.contains("_.") || trimmed.contains("._")) {
            return "No consecutive special characters"
        }
        if (reservedUsernames.contains(trimmed.lowercase())) {
            return "This username is reserved"
        }
        if (trimmed.all { it.isDigit() }) return "Username cannot be only numbers"
        return null
    }

    suspend fun isUsernameAvailable(username: String): Boolean {
        if (username.isBlank() || username.length < 3) return false
        val normalized = username.lowercase().trim()
        return try {
            val doc = firestore.collection(USERNAMES_COLLECTION).document(normalized).get().await()
            !doc.exists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun registerUserProfile(
        context: Context,
        displayName: String,
        username: String,
        photoUrl: String?,
        region: String? = null,
        vibes: List<String>? = null
    ): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        val normalizedUsername = username.lowercase().trim()

        return try {
            firestore.runTransaction { transaction ->
                val usernameDoc = firestore.collection(USERNAMES_COLLECTION).document(normalizedUsername)
                val userDoc = firestore.collection(USERS_COLLECTION).document(user.uid)
                
                val existingUsernameDoc = transaction.get(usernameDoc)
                val existingUserDoc = transaction.get(userDoc)

                if (existingUsernameDoc.exists() && existingUsernameDoc.getString("uid") != user.uid) {
                    throw Exception("Username already taken")
                }

                val userData = mutableMapOf<String, Any>(
                    "displayName" to displayName,
                    "username" to normalizedUsername,
                    "uid" to user.uid,
                    "updatedAt" to System.currentTimeMillis()
                )
                
                if (existingUserDoc.exists()) {
                    val oldUsername = existingUserDoc.getString("username")
                    if (oldUsername == normalizedUsername) {
                        existingUserDoc.getLong("lastUsernameChange")?.let { userData["lastUsernameChange"] = it }
                    } else {
                        userData["lastUsernameChange"] = System.currentTimeMillis()
                    }
                } else {
                    userData["lastUsernameChange"] = System.currentTimeMillis()
                }

                photoUrl?.let { userData["photoUrl"] = it }
                region?.let { userData["region"] = it }
                vibes?.let { userData["vibes"] = it }

                transaction.set(usernameDoc, mapOf("uid" to user.uid))
                transaction.set(userDoc, userData)
            }.await()

            context.preferences.edit {
                putString(displayNameKey, displayName)
                putString(usernameKey, normalizedUsername)
                photoUrl?.let { putString(profileImageUrlKey, it) }
                region?.let { putString(com.github.musicyou.utils.contentRegionKey, it) }
                vibes?.let { putString(com.github.musicyou.utils.favoriteGenresKey, it.joinToString(",")) }
            }
            
            com.github.musicyou.sync.SyncPreferences.setUserName(context, displayName)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncGoogleProfile(context: Context): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        
        return try {
            user.reload().await()
            val freshUser = auth.currentUser
            
            val googleProfile = freshUser?.providerData?.find { it.providerId == "google.com" }
            val photoUrl = googleProfile?.photoUrl?.toString()
                ?: return Result.failure(Exception("No Google Profile picture found."))

            firestore.collection(USERS_COLLECTION).document(user.uid)
                .update("photoUrl", photoUrl).await()

            val timestamp = System.currentTimeMillis()
            context.preferences.edit {
                putString(profileImageUrlKey, photoUrl)
                putLong(profileImageLastUpdatedKey, timestamp)
            }
            
            Result.success(Unit)
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUsername(context: Context, newUsername: String): Result<Unit> {
        return updateUserProfile(context, newUsername = newUsername)
    }

    suspend fun updateUserProfile(
        context: Context,
        displayName: String? = null,
        newUsername: String? = null,
        photoUrl: String? = null
    ): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        val monthInMs = 30L * 24 * 60 * 60 * 1000

        return try {
            firestore.runTransaction { transaction ->
                val userDoc = firestore.collection(USERS_COLLECTION).document(user.uid)
                val snapshot = transaction.get(userDoc)
                
                var finalUsername = snapshot.getString("username")
                
                if (newUsername != null) {
                    val normalized = newUsername.lowercase().trim()
                    val oldUsername = snapshot.getString("username")
                    
                    if (oldUsername != normalized) {
                        val lastChange = snapshot.getLong("lastUsernameChange") ?: 0L
                        if (System.currentTimeMillis() - lastChange < monthInMs) {
                            val daysLeft = 30 - (System.currentTimeMillis() - lastChange) / (24 * 60 * 60 * 1000)
                            throw Exception("Username can only be changed once a month. Try again in $daysLeft days.")
                        }

                        val newUsernameDoc = firestore.collection(USERNAMES_COLLECTION).document(normalized)
                        if (transaction.get(newUsernameDoc).exists()) {
                            throw Exception("Username already taken")
                        }
                        
                        oldUsername?.let {
                            transaction.delete(firestore.collection(USERNAMES_COLLECTION).document(it))
                        }
                        
                        transaction.set(newUsernameDoc, mapOf("uid" to user.uid))
                        transaction.update(userDoc, mapOf(
                            "username" to normalized,
                            "lastUsernameChange" to System.currentTimeMillis()
                        ))
                        finalUsername = normalized
                    }
                }

                val updates = mutableMapOf<String, Any>()
                displayName?.let { updates["displayName"] = it }
                photoUrl?.let { updates["photoUrl"] = it }
                
                if (updates.isNotEmpty()) {
                    transaction.update(userDoc, updates)
                }
                
                mapOf(
                    "username" to finalUsername,
                    "displayName" to (displayName ?: snapshot.getString("displayName")),
                    "photoUrl" to (photoUrl ?: snapshot.getString("photoUrl"))
                )

            }.await().let { result ->
                context.preferences.edit {
                    result["username"]?.let { putString(usernameKey, it as String) }
                    result["displayName"]?.let { putString(displayNameKey, it as String) }
                    result["photoUrl"]?.let { putString(profileImageUrlKey, it as String) }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUserProfile(context: Context): Boolean {
        val user = auth.currentUser ?: run {
            android.util.Log.w("ProfileManager", "fetchUserProfile: No authenticated user")
            return false
        }
        
        android.util.Log.d("ProfileManager", "fetchUserProfile: Fetching profile for UID: ${user.uid}")
        
        return try {
            val doc = firestore.collection(USERS_COLLECTION).document(user.uid).get().await()
            
            android.util.Log.d("ProfileManager", "fetchUserProfile: Document exists: ${doc.exists()}")
            
            if (doc.exists()) {
                val displayName = doc.getString("displayName") ?: ""
                val username = doc.getString("username") ?: ""
                val photoUrl = doc.getString("photoUrl")
                
                android.util.Log.d("ProfileManager", "fetchUserProfile: Found profile - name: '$displayName', username: '$username'")
                
                context.preferences.edit {
                    putString(displayNameKey, displayName)
                    putString(usernameKey, username)
                    photoUrl?.let { putString(profileImageUrlKey, it) }
                    doc.getString("region")?.let { putString(com.github.musicyou.utils.contentRegionKey, it) }
                    @Suppress("UNCHECKED_CAST")
                    (doc.get("vibes") as? List<String>)?.let { 
                        putString(com.github.musicyou.utils.favoriteGenresKey, it.joinToString(",")) 
                    }
                }
                
                com.github.musicyou.sync.SyncPreferences.setUserName(context, displayName)
                
                android.util.Log.d("ProfileManager", "fetchUserProfile: Successfully loaded profile")
                true
            } else {
                android.util.Log.w("ProfileManager", "fetchUserProfile: Document does not exist for UID: ${user.uid}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileManager", "fetchUserProfile: Error fetching profile", e)
            false
        }
    }

    // ==========================================
    // FRIEND REQUEST SYSTEM (Instagram-style)
    // ==========================================

    /**
     * Sends a friend request to a user by username.
     */
    suspend fun sendFriendRequest(targetUsername: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        val normalized = targetUsername.lowercase().trim().removePrefix("@")
        
        return try {
            // 1. Find Target UID
            val usernameDoc = firestore.collection(USERNAMES_COLLECTION).document(normalized).get().await()
            if (!usernameDoc.exists()) throw Exception("User does not exist")
            
            val targetUid = usernameDoc.getString("uid") ?: throw Exception("User does not exist")
            if (targetUid == user.uid) throw Exception("Cannot send request to yourself")

            // 2. Check overlap
            val alreadyFriends = firestore.collection(USERS_COLLECTION).document(user.uid)
                .collection("friends").document(targetUid).get().await().exists()
            if (alreadyFriends) throw Exception("Already friends")

            val alreadyRequested = firestore.collection(USERS_COLLECTION).document(targetUid)
                .collection("requests").document(user.uid).get().await().exists()
            if (alreadyRequested) throw Exception("Request already sent")

            // 3. Send Request (Add to target's 'requests' subcollection)
            val requestData = mapOf(
                "uid" to user.uid,
                "timestamp" to System.currentTimeMillis()
            )
            firestore.collection(USERS_COLLECTION).document(targetUid)
                .collection("requests").document(user.uid).set(requestData).await()
                
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches incoming friend requests with sender profile data.
     */
    suspend fun getIncomingRequests(): Result<List<Map<String, Any>>> = coroutineScope {
        val user = auth.currentUser ?: return@coroutineScope Result.failure(Exception("User not logged in"))
        try {
            val snapshot = firestore.collection(USERS_COLLECTION).document(user.uid)
                .collection("requests").get().await()

            // Parallel fetch of profiles
            val requests = snapshot.documents.map { doc ->
                async {
                    val uid = doc.id
                    val profileDoc = firestore.collection(USERS_COLLECTION).document(uid).get().await()
                    if (profileDoc.exists()) {
                        mapOf(
                            "uid" to uid,
                            "displayName" to (profileDoc.getString("displayName") ?: "Unknown"),
                            "username" to (profileDoc.getString("username") ?: "unknown"),
                            "photoUrl" to (profileDoc.getString("photoUrl") ?: ""),
                            "timestamp" to (doc.getLong("timestamp") ?: 0L)
                        )
                    } else {
                        null // User deleted?
                    }
                }
            }.mapNotNull { it.await() }

            Result.success(requests)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Accepts a friend request.
     */
    suspend fun acceptFriendRequest(requestUid: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        return try {
            firestore.runTransaction { transaction ->
                val myFriendsRef = firestore.collection(USERS_COLLECTION).document(user.uid).collection("friends").document(requestUid)
                val theirFriendsRef = firestore.collection(USERS_COLLECTION).document(requestUid).collection("friends").document(user.uid)
                val requestRef = firestore.collection(USERS_COLLECTION).document(user.uid).collection("requests").document(requestUid)

                // Add to both friends lists
                val data = mapOf("addedAt" to System.currentTimeMillis(), "uid" to requestUid) // redundant uid but helpful
                val theirData = mapOf("addedAt" to System.currentTimeMillis(), "uid" to user.uid)
                
                transaction.set(myFriendsRef, data)
                transaction.set(theirFriendsRef, theirData)
                
                // Remove request
                transaction.delete(requestRef)
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rejects (deletes) a friend request.
     */
    suspend fun rejectFriendRequest(requestUid: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        return try {
            firestore.collection(USERS_COLLECTION).document(user.uid)
                .collection("requests").document(requestUid).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets approved friends list with FULL profile data.
     */
    suspend fun getFriends(): Result<List<Map<String, Any>>> = coroutineScope {
        val user = auth.currentUser ?: return@coroutineScope Result.failure(Exception("User not logged in"))
        try {
            val snapshot = firestore.collection(USERS_COLLECTION).document(user.uid)
                .collection("friends").get().await()
            
            val friends = snapshot.documents.map { doc ->
                async {
                    val uid = doc.id
                    val profileDoc = firestore.collection(USERS_COLLECTION).document(uid).get().await()
                    if (profileDoc.exists()) {
                        mapOf(
                            "uid" to uid,
                            "displayName" to (profileDoc.getString("displayName") ?: "Unknown"),
                            "username" to (profileDoc.getString("username") ?: "unknown"),
                            "photoUrl" to (profileDoc.getString("photoUrl") ?: ""),
                            "updatedAt" to (profileDoc.getLong("updatedAt") ?: 0L),
                            "addedAt" to (doc.getLong("addedAt") ?: 0L)
                        )
                    } else {
                        // Handle deleted user - keep simple or show as "Deleted User"
                        mapOf(
                            "uid" to uid,
                            "displayName" to "Deleted User",
                            "username" to "unknown",
                            "photoUrl" to "",
                            "updatedAt" to 0L,
                            "addedAt" to (doc.getLong("addedAt") ?: 0L)
                        )
                    }
                }
            }.map { it.await() } // Wait for all parallel fetches

            Result.success(friends)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

     /**
      * LEGACY: Support for old calls if any, by redirecting to sendFriendRequest logic if possible 
      * or removed if strict. For safety, let's keep it but ideally it shouldn't be used instantly anymore.
      * Converting to behave like sendFriendRequest for now? No, existing UI calls it.
      * We will update UI to call sendFriendRequest.
      * Keeping this for backward compatibility if needed but marking deprecated.
      */
    suspend fun addFriend(username: String) = sendFriendRequest(username) 

    /**
     * Removes a friend (Mutual Unfollow).
     * Uses suspend .await() to avoid blocking the main thread.
     */
    suspend fun removeFriend(friendUid: String): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
            
            // Delete from my friends
            firestore.collection(USERS_COLLECTION)
                .document(user.uid)
                .collection("friends")
                .document(friendUid)
                .delete()
                .await()

            // Delete from friend's friends
            firestore.collection(USERS_COLLECTION)
                .document(friendUid)
                .collection("friends")
                .document(user.uid)
                .delete()
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("ProfileManager", "removeFriend failed", e)
            Result.failure(e)
        }
    }

    suspend fun deleteAccount(context: Context): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        
        android.util.Log.d("ProfileManager", "deleteAccount: Starting deletion for UID: ${user.uid}")
        
        return try {
            // 1. Get user data first
            val userDoc = firestore.collection(USERS_COLLECTION).document(user.uid).get().await()
            val username = if (userDoc.exists()) userDoc.getString("username") else null
            
            android.util.Log.d("ProfileManager", "deleteAccount: Found username: $username")
            
            // 2. Fetch all data to delete
            // Use parallel execution for fetching to be faster
            val (friendsSnapshot, requestsSnapshot, presenceSnapshot) = coroutineScope {
                val f = async { 
                    firestore.collection(USERS_COLLECTION).document(user.uid).collection("friends").get().await() 
                }
                val r = async { 
                    firestore.collection(USERS_COLLECTION).document(user.uid).collection("requests").get().await() 
                }
                val p = async { 
                    firestore.collection(USERS_COLLECTION).document(user.uid).collection("presence").get().await() 
                }
                Triple(f.await(), r.await(), p.await())
            }
            
            android.util.Log.d("ProfileManager", "deleteAccount: Fetch complete. Friends: ${friendsSnapshot.size()}, Requests: ${requestsSnapshot.size()}")
            
            // 3. Prepare all deletion references
            val allDeletions = mutableListOf<com.google.firebase.firestore.DocumentReference>()
            
            // Subcollections
            allDeletions.addAll(friendsSnapshot.documents.map { it.reference })
            allDeletions.addAll(requestsSnapshot.documents.map { it.reference })
            allDeletions.addAll(presenceSnapshot.documents.map { it.reference })
            
            // Main document
            allDeletions.add(firestore.collection(USERS_COLLECTION).document(user.uid))
            
            // Username reservation
            if (!username.isNullOrBlank()) {
                allDeletions.add(firestore.collection(USERNAMES_COLLECTION).document(username))
            }
            
            // 4. Execute Batched Deletes (Chunked to respect 500 limit)
            // Using 400 to be safe
            val chunks = allDeletions.chunked(400)
            android.util.Log.d("ProfileManager", "deleteAccount: Processing ${chunks.size} deletion batches")
            
            chunks.forEachIndexed { index, chunk ->
                val batch = firestore.batch()
                chunk.forEach { ref -> batch.delete(ref) }
                batch.commit().await()
                android.util.Log.d("ProfileManager", "deleteAccount: Batch $index/${chunks.size} committed")
            }
            
            // 5. Clean up friend references (remove yourself from others' friend lists)
            // This is done individually as we can't batch across parent collections easily in a loop without hitting limits
            // We use a supervisorScope so one failure doesn't stop the rest
            coroutineScope {
                val jobs = friendsSnapshot.documents.map { friendDoc ->
                    launch {
                        try {
                            firestore.collection(USERS_COLLECTION)
                                .document(friendDoc.id)
                                .collection("friends")
                                .document(user.uid)
                                .delete()
                                .await()
                        } catch (e: Exception) {
                            android.util.Log.w("ProfileManager", "deleteAccount: Failed to unlink from friend ${friendDoc.id}: ${e.message}")
                        }
                    }
                }
                jobs.joinAll() // Wait for all cleanups
            }
            
            // 6. Delete Firebase Auth account
            // This is the point of no return
            try {
                user.delete().await()
                android.util.Log.d("ProfileManager", "deleteAccount: Firebase Auth account deleted")
            } catch (e: Exception) {
                // If this fails (e.g. requires re-auth), we should let the user know
                // BUT the data is already gone. Ideally we should have checked re-auth BEFORE deleting data.
                // However, we can't trigger re-auth easily here without UI.
                // We re-throw so UI can show "Login again to finish deletion" if needed.
                throw e
            }
            
            // 7. Clear local data
            try {
                context.preferences.edit { clear() }
                context.cacheDir.deleteRecursively()
                android.util.Log.d("ProfileManager", "deleteAccount: Cache cleared")
            } catch (e: Exception) {
                android.util.Log.w("ProfileManager", "deleteAccount: Failed to clear cache: ${e.message}")
            }
            
            android.util.Log.d("ProfileManager", "deleteAccount: Account deletion successful!")
            Result.success(Unit)
            
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            android.util.Log.e("ProfileManager", "deleteAccount: Re-authentication required", e)
            Result.failure(Exception("Security Check: Please log in again and retry deletion."))
        } catch (e: Exception) {
            android.util.Log.e("ProfileManager", "deleteAccount: Deletion failed", e)
            Result.failure(e)
        }
    }

    // ==========================================
    // PRESENCE TRACKING SYSTEM
    // ==========================================

    /**
     * Updates the current user's presence status in Firestore.
     * @param status "hosting", "participating", or "idle"
     * @param sessionId Optional session ID if hosting/participating
     */
    suspend fun updatePresence(status: String, sessionId: String? = null): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        return try {
            val presenceData = mutableMapOf<String, Any?>(
                "status" to status,
                "lastUpdated" to System.currentTimeMillis()
            )
            
            if (sessionId != null) {
                presenceData["sessionId"] = sessionId
            } else {
                // Remove sessionId field if null
                presenceData["sessionId"] = com.google.firebase.firestore.FieldValue.delete()
            }
            
            firestore.collection(USERS_COLLECTION)
                .document(user.uid)
                .collection("presence")
                .document("current")
                .set(presenceData)
                .await()
                
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observes presence status of all friends in real-time.
     * Returns a Flow that emits whenever any friend's presence changes.
     */
    fun observeFriendsPresence(): Flow<List<FriendPresence>> = callbackFlow {
        val user = auth.currentUser
        if (user == null) {
            android.util.Log.w("ProfileManager", "observeFriendsPresence: User not logged in, returning empty")
            close()
            return@callbackFlow
        }

        try {
            // First, get the list of friend UIDs
            val friendsSnapshot = firestore.collection(USERS_COLLECTION)
                .document(user.uid)
                .collection("friends")
                .get()
                .await()

            val friendUids = friendsSnapshot.documents.map { it.id }

            if (friendUids.isEmpty()) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }

            // Create individual listeners for each friend's presence
            val listeners = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()
            val presenceMap = mutableMapOf<String, FriendPresence>()

            friendUids.forEach { friendUid ->
                val listener = firestore.collection(USERS_COLLECTION)
                    .document(friendUid)
                    .collection("presence")
                    .document("current")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.e("ProfileManager", "Friend presence listener error", error)
                            return@addSnapshotListener
                        }

                        if (snapshot != null && snapshot.exists()) {
                            val status = snapshot.getString("status") ?: "idle"
                            val sessionId = snapshot.getString("sessionId")
                            val lastUpdated = snapshot.getLong("lastUpdated") ?: 0L

                            presenceMap[friendUid] = FriendPresence(
                                uid = friendUid,
                                status = status,
                                sessionId = sessionId,
                                lastUpdated = lastUpdated
                            )
                        } else {
                            // No presence document = idle/offline
                            presenceMap[friendUid] = FriendPresence(
                                uid = friendUid,
                                status = "idle",
                                sessionId = null,
                                lastUpdated = 0L
                            )
                        }

                        // Emit updated list
                        trySend(presenceMap.values.toList())
                    }

                listeners.add(listener)
            }

            // Clean up listeners when Flow is cancelled
            awaitClose {
                listeners.forEach { it.remove() }
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileManager", "observeFriendsPresence: Error setting up observers", e)
            close() // Close gracefully instead of crashing downstream collectors
        }
    }

    /**
 * Fetches public profile details for a given UID.
 * Returns a PublicProfile with country and favoriteGenres.
 */
suspend fun getPublicProfile(uid: String): Result<PublicProfile> = coroutineScope {
    try {
        val doc = firestore.collection(USERS_COLLECTION).document(uid).get().await()
        if (doc.exists()) {
            val profile = PublicProfile(
                uid = uid,
                displayName = doc.getString("displayName") ?: "Unknown",
                username = doc.getString("username") ?: "unknown",
                photoUrl = doc.getString("photoUrl"),
                country = doc.getString("region"),  // Using 'region' field from Firestore
                favoriteGenres = (doc.get("vibes") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                bio = doc.getString("bio")
            )
            Result.success(profile)
        } else {
            Result.failure(Exception("User not found"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

    /**
     * Gets the current user's UID safely.
     */
    fun getCurrentUserUid(): String? = auth.currentUser?.uid

    // ==========================================
    // PUBLIC PLAYLISTS
    // ==========================================

    data class PublicPlaylist(
        val id: String,
        val name: String,
        val songCount: Int,
        val coverUrl: String? = null
    )

    data class PlaylistSong(
        val mediaId: String,
        val title: String,
        val artist: String,
        val duration: Long,
        val thumbnailUrl: String?
    ) {
        fun asMediaItem(): androidx.media3.common.MediaItem {
            return androidx.media3.common.MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(mediaId)
                .setCustomCacheKey(mediaId) // Important for player caching/resolution
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(if (thumbnailUrl != null) android.net.Uri.parse(thumbnailUrl) else null)
                        .setExtras(
                            androidx.core.os.bundleOf(
                                "durationText" to com.github.musicyou.utils.formatAsDuration(duration)
                            )
                        )
                        .build()
                )
                .build()
        }
    }

    suspend fun getPublicPlaylists(uid: String): Result<List<PublicPlaylist>> = coroutineScope {
        try {
            val snapshot = firestore.collection(USERS_COLLECTION).document(uid)
                .collection("public_playlists").get().await()

            val playlists = snapshot.documents.map { doc ->
                PublicPlaylist(
                    id = doc.id,
                    name = doc.getString("name") ?: "Untitled Playlist",
                    songCount = doc.getLong("songCount")?.toInt() ?: 0,
                    coverUrl = doc.getString("coverUrl")
                )
            }
            Result.success(playlists)
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error fetching public playlists for $uid", e)
            Result.failure(e)
        }
    }

    suspend fun getPublicPlaylistSongs(uid: String, playlistId: String): Result<List<PlaylistSong>> = coroutineScope {
        try {
            val snapshot = firestore.collection(USERS_COLLECTION).document(uid)
                .collection("public_playlists").document(playlistId)
                .collection("songs").orderBy("position").get().await()

            val songs = snapshot.documents.mapNotNull { doc ->
                 try {
                     PlaylistSong(
                         mediaId = doc.getString("mediaId") ?: return@mapNotNull null,
                         title = doc.getString("title") ?: "Unknown Title",
                         artist = doc.getString("artist") ?: "Unknown Artist",
                         duration = doc.getLong("duration") ?: 0L,
                         thumbnailUrl = doc.getString("thumbnailUrl")
                     )
                 } catch (e: Exception) {
                     null
                 }
            }
            Result.success(songs)
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error fetching songs for playlist $playlistId of user $uid", e)
            Result.failure(e)
        }
    }


    // ==========================================
    // PRIVACY & LIKED SONGS
    // ==========================================

    data class PrivacySettings(
        val shareLikedSongs: Boolean = true,
        val sharePlaylists: Boolean = true,
        val showActivity: Boolean = true
    )

    suspend fun getPrivacySettings(): Result<PrivacySettings> = coroutineScope {
        try {
            val uid = auth.currentUser?.uid ?: return@coroutineScope Result.failure(Exception("No user"))
            val snapshot = firestore.collection(USERS_COLLECTION).document(uid).get().await()
            val privacy = snapshot.get("privacy") as? Map<String, Boolean>
            
            Result.success(
                PrivacySettings(
                    shareLikedSongs = privacy?.get("shareLikedSongs") ?: true,
                    sharePlaylists = privacy?.get("sharePlaylists") ?: true,
                    showActivity = privacy?.get("showActivity") ?: true
                )
            )
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error fetching privacy settings", e)
            Result.failure(e)
        }
    }

    suspend fun updatePrivacySettings(settings: PrivacySettings) = coroutineScope {
        try {
            val uid = auth.currentUser?.uid ?: return@coroutineScope Result.failure(Exception("No user"))
            firestore.collection(USERS_COLLECTION).document(uid)
                .update("privacy", mapOf(
                    "shareLikedSongs" to settings.shareLikedSongs,
                    "sharePlaylists" to settings.sharePlaylists,
                    "showActivity" to settings.showActivity
                )).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error updating privacy settings", e)
            Result.failure(e)
        }
    }
    
    suspend fun getPublicLikedSongs(uid: String): Result<List<PlaylistSong>> = coroutineScope {
        try {
            // Check privacy first (or rely on security rules failing)
            // It's better UI UX to check metadata if available, but here we'll try fetch.
            // Note: Data is in userData/{uid}/favorites/all
            
            val snapshot = firestore.collection("userData").document(uid)
                .collection("favorites").document("all").get().await()
                
            if (!snapshot.exists()) return@coroutineScope Result.success(emptyList())

            val songsData = snapshot.get("songs") as? List<Map<String, Any>>
            val songs = songsData?.mapNotNull { data ->
                try {
                     PlaylistSong(
                         mediaId = data["id"] as String,
                         title = data["title"] as String,
                         artist = data["artistsText"] as? String ?: "Unknown Artist",
                         duration = 0L, // Duration might not be in favorites backup, or as string
                         thumbnailUrl = data["thumbnailUrl"] as? String
                     )
                } catch (e: Exception) { null }
            } ?: emptyList()
            
            Result.success(songs)
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error fetching public liked songs for $uid", e)
            Result.failure(e)
        }
    }
}
