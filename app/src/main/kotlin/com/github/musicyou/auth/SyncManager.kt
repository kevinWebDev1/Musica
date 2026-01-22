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
import kotlinx.coroutines.tasks.await

/**
 * Handles backup and restore of user data (Favorites, Playlists, History) using Firestore.
 */
object SyncManager {
    private val firestore = Firebase.firestore
    private val auth = Firebase.auth
    private const val DATA_COLLECTION = "userData"

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
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup favorites", e)
        }
    }

    /**
     * Backs up local playlists to Firestore.
     */
    suspend fun backupPlaylists() {
        val user = auth.currentUser ?: return
        try {
            val previews = Database.playlistPreviewsByNameAsc().first()
            for (preview in previews) {
                val songs = Database.playlistSongs(preview.id).first()
                val playlistData = mapOf(
                    "name" to preview.name,
                    "songs" to songs.map { it.id }
                )
                firestore.collection(DATA_COLLECTION).document(user.uid)
                    .collection("playlists").document(preview.id.toString()).set(playlistData).await()
            }
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
    suspend fun backupSinglePlaylist(playlistId: Long) {
        val user = auth.currentUser ?: return
        try {
            val playlist = Database.playlist(playlistId).first() ?: return
            val songs = Database.playlistSongs(playlistId).first()
            
            val playlistData = mapOf(
                "name" to playlist.name,
                "songs" to songs.map { it.id }
            )
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("playlists").document(playlistId.toString()).set(playlistData).await()
        } catch (e: Exception) {
            Log.e("SyncManager", "Failed to backup playlist $playlistId", e)
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
            firestore.collection(DATA_COLLECTION).document(user.uid)
                .collection("playlists").document(playlistId.toString()).delete().await()
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
