package com.github.musicyou.auth

import android.content.Context
import android.util.Log
import com.github.musicyou.Database
import com.github.musicyou.models.Playlist
import com.github.musicyou.models.Song
import com.github.musicyou.query
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Handles backup and restore of user data (Favorites, Playlists, History) using Firestore.
 */
object SyncManager {
    private val firestore = Firebase.firestore
    private val auth = Firebase.auth
    private const val DATA_COLLECTION = "userData"
    
    // Internal scope for background sync operations that must survive UI lifecycle
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun triggerBackupFavorites() {
        scope.launch {
             backupFavorites()
        }
    }
    
    fun triggerBackupPlaylist(playlistId: Long) {
        scope.launch {
            backupSinglePlaylist(playlistId)
        }
    }

    fun triggerDeletePlaylistBackup(playlistId: Long) {
        scope.launch {
            deletePlaylistBackup(playlistId)
        }
    }

    /**
     * Backs up favorites to Firestore.
     */
    suspend fun backupFavorites() {
        val user = auth.currentUser ?: return
        try {
            val favorites = Database.favorites().first()
            val data = favorites.map { song ->
                mapOf(
                    "id" to song.id,
                    "title" to song.title,
                    "artistsText" to song.artistsText,
                    "durationText" to song.durationText,
                    "thumbnailUrl" to song.thumbnailUrl,
                    "likedAt" to song.likedAt
                )
            }
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("favorites").document("all").set(mapOf("songs" to data)).await()
            Log.d("SyncManager", "Backup favorites success: ${favorites.size} songs")
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup favorites", e)
        }
    }

    /**
     * Backs up local playlists to Firestore.
     */
    /**
     * Backs up local playlists to Firestore.
     */
    suspend fun backupPlaylists() {
        // val user = auth.currentUser ?: return // managed by backupSinglePlaylist
        try {
            val previews = Database.playlistPreviewsByNameAsc().first()
            for (preview in previews) {
                backupSinglePlaylist(preview.id)
            }
            Log.d("SyncManager", "Backup playlists success: ${previews.size} playlists")
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup playlists", e)
        }
    }

    /**
     * Backs up listening history (Events) to Firestore.
     */
    suspend fun backupHistory() {
        val user = auth.currentUser ?: return
        try {
            val events = Database.events().first()
            // We need song metadata to restore correctly on new devices
            val allSongs = Database.songsByRowIdAsc().first().associateBy { it.id }
            
            val data = events.mapNotNull { event ->
                val song = allSongs[event.songId] ?: return@mapNotNull null
                mapOf(
                    "songId" to event.songId,
                    "timestamp" to event.timestamp,
                    "playTime" to event.playTime,
                    // Song Metadata
                    "title" to song.title,
                    "artistsText" to song.artistsText,
                    "durationText" to song.durationText,
                    "thumbnailUrl" to song.thumbnailUrl
                    // events don't track likedAt, favorites does
                )
            }
            
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("history").document("all").set(mapOf("events" to data)).await()
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup history", e)
        }
    }

    /**
     * Backs up a specific playlist to Firestore.
     */
    /**
     * Backs up a specific playlist to Firestore.
     * Also publishes it to 'public_playlists' for social features.
     */
    suspend fun backupSinglePlaylist(playlistId: Long) {
        val user = auth.currentUser ?: return
        try {
            val playlist = Database.playlist(playlistId).first() ?: return
            val songs = Database.playlistSongs(playlistId).first()
            
            // 1. Private Backup (Lightweight)
            val playlistData = mapOf(
                "name" to playlist.name,
                "songs" to songs.map { it.id }
            )
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("playlists").document(playlistId.toString()).set(playlistData).await()
                
            // 2. Public Publish (Rich Metadata)
            val publicRef = firestore.collection("users").document(user.uid)
                .collection("public_playlists").document(playlistId.toString())
            
            val publicData = mapOf(
                "id" to playlistId.toString(),
                "name" to playlist.name,
                "songCount" to songs.size,
                "coverUrl" to songs.firstOrNull()?.thumbnailUrl
            )
            publicRef.set(publicData).await()
            
            // Batch write songs to subcollection
            // Delete old songs first (inefficient but safe for strict ordering/removed songs)
            // Or strictly overwrite. Overwriting by ID is fine if IDs match. But position might change.
            // Safest: Delete all in subcollection, then add.
            val oldSongs = publicRef.collection("songs").get().await()
            val batch = firestore.batch()
            oldSongs.documents.forEach { batch.delete(it.reference) }
            
            songs.forEachIndexed { index, song ->
                val songDoc = publicRef.collection("songs").document(song.id) // Use mediaId/songId
                
                // Parse duration
                val durationMs = song.durationText?.let { text ->
                    val parts = text.split(":").reversed()
                    var seconds = 0L
                    var multiplier = 1L
                    for (part in parts) {
                         seconds += (part.toLongOrNull() ?: 0L) * multiplier
                         multiplier *= 60
                    }
                    seconds * 1000L
                } ?: 0L

                val songData = mapOf(
                    "mediaId" to song.id,
                    "title" to song.title,
                    "artist" to song.artistsText,
                    "duration" to durationMs,
                    "thumbnailUrl" to song.thumbnailUrl,
                    "position" to index
                )
                batch.set(songDoc, songData)
            }
            batch.commit().await()
            Log.d("SyncManager", "Backup/Publish playlist $playlistId success")
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup/publish playlist $playlistId", e)
        }
    }

    /**
     * Restores user data from Firestore.
     */
    suspend fun restoreUserData(context: Context) {
        val user = auth.currentUser ?: return
        try {
            // 1. Restore Favorites
            val favDoc = firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("favorites").document("all").get().await()
            
            if (favDoc.exists()) {
                val songsData = favDoc.get("songs") as? List<Map<String, Any>>
                songsData?.forEach { data ->
                    val song = Song(
                        id = data["id"] as String,
                        title = data["title"] as String,
                        artistsText = data["artistsText"] as? String,
                        durationText = data["durationText"] as? String,
                        thumbnailUrl = data["thumbnailUrl"] as? String,
                        likedAt = (data["likedAt"] as? Number)?.toLong()
                    )
                    query { Database.insert(song) }
                }
            }

            // 2. Restore History
            val historyDoc = firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("history").document("all").get().await()
                
            if (historyDoc.exists()) {
                val eventsData = historyDoc.get("events") as? List<Map<String, Any>>
                val deferredEvents = mutableListOf<com.github.musicyou.models.Event>()
                
                eventsData?.forEach { data ->
                    // Restore Song first
                    val song = Song(
                        id = data["songId"] as String,
                        title = data["title"] as String,
                        artistsText = data["artistsText"] as? String,
                        durationText = data["durationText"] as? String,
                        thumbnailUrl = data["thumbnailUrl"] as? String
                    )
                    query { Database.insert(song) }
                    
                    deferredEvents.add(
                        com.github.musicyou.models.Event(
                            songId = data["songId"] as String,
                            timestamp = (data["timestamp"] as Number).toLong(),
                            playTime = (data["playTime"] as Number).toLong()
                        )
                    )
                }
                // Batch insert events
                deferredEvents.forEach { event ->
                    query { Database.insert(event) }
                }
                
                // Recalculate song stats so they appear in library (filtering by totalPlayTimeMs > 0)
                query { Database.recalculateSongStats() }
            }

            // 3. Restore Playlists
            val playlistsSnapshot = firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("playlists").get().await()
            
            playlistsSnapshot.documents.forEach { doc ->
                val name = doc.getString("name") ?: return@forEach
                val songIds = doc.get("songs") as? List<String> ?: return@forEach
                
                query {
                    val playlistId = Database.insert(Playlist(name = name))
                    songIds.forEachIndexed { index, songId ->
                        Database.insert(com.github.musicyou.models.SongPlaylistMap(
                            songId = songId,
                            playlistId = playlistId,
                            position = index
                        ))
                    }
                }
            }
            
            Log.d("SyncManager", "Data restoration complete")
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to restore user data", e)
        }
    }

    /**
     * Deletes a playlist backup from Firestore.
     */
    suspend fun deletePlaylistBackup(playlistId: Long) {
        val user = auth.currentUser ?: return
        try {
            // 1. Delete Private Backup
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("playlists").document(playlistId.toString()).delete().await()

            // 2. Delete Public Playlist
            firestore.collection("users").document(user.uid)
                .collection("public_playlists").document(playlistId.toString()).delete().await()
                
            Log.d("SyncManager", "Delete playlist $playlistId backup/public success")
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to delete playlist backup", e)
        }
    }

    /**
     * Creates a shareable link for a playlist.
     * For local playlists, this generates a deep link format.
     */
    fun createShareLink(playlistId: Long): String {
        val user = auth.currentUser ?: return ""
        return "https://musicyou.app/share/playlist/${user.uid}/$playlistId"
    }
}
