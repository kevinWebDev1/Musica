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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val user = auth.currentUser ?: return false
        return try {
            val doc = firestore.collection(USERS_COLLECTION).document(user.uid).get().await()
            if (doc.exists()) {
                val displayName = doc.getString("displayName") ?: ""
                val username = doc.getString("username") ?: ""
                val photoUrl = doc.getString("photoUrl")
                
                context.preferences.edit {
                    putString(displayNameKey, displayName)
                    putString(usernameKey, username)
                    photoUrl?.let { putString(profileImageUrlKey, it) }
                    doc.getString("region")?.let { putString(com.github.musicyou.utils.contentRegionKey, it) }
                    @Suppress("UNCHECKED_CAST")
                    (doc.get("vibes") as? List<String>)?.let { 
                        putString(com.github.musicyou.utils.favoriteGenresKey, it.joinToString(",")) 
                    }
                    putBoolean(onboardedKey, true)
                }
                
                com.github.musicyou.sync.SyncPreferences.setUserName(context, displayName)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
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
     */
    suspend fun removeFriend(friendUid: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        return try {
            firestore.runTransaction { transaction ->
                val myFriendDoc = firestore.collection(USERS_COLLECTION).document(user.uid)
                    .collection("friends").document(friendUid)
                val theirFriendDoc = firestore.collection(USERS_COLLECTION).document(friendUid)
                    .collection("friends").document(user.uid)
                
                transaction.delete(myFriendDoc)
                transaction.delete(theirFriendDoc)
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAccount(context: Context): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("User not logged in"))
        return try {
            firestore.runTransaction { transaction ->
                val userDocRef = firestore.collection(USERS_COLLECTION).document(user.uid)
                val snapshot = transaction.get(userDocRef)
                
                val username = snapshot.getString("username")
                if (!username.isNullOrBlank()) {
                    transaction.delete(firestore.collection(USERNAMES_COLLECTION).document(username))
                }
                
                transaction.delete(userDocRef)
            }.await()

            user.delete().await()
            context.preferences.edit { clear() }
            Result.success(Unit)
        } catch (e: Exception) {
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
            close(Exception("User not logged in"))
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
                            // Log error but continue listening to other friends
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
            close(e)
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
}
