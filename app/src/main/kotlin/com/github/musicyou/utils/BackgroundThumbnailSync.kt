package com.github.musicyou.utils

import android.util.Log
import com.github.innertube.Innertube
import com.github.innertube.requests.albumPage
import com.github.innertube.requests.artistPage
import com.github.musicyou.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object BackgroundThumbnailSync {

    private val TAG = "BackgroundThumbnailSync"
    private var isSyncing = false

    fun start(scope: CoroutineScope) {
        if (isSyncing) return
        isSyncing = true

        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Background Thumbnail Sync...")

            while (isActive) {
                var processedAny = false

                // 1. Process Albums
                try {
                    val missingAlbums = Database.albumsWithoutThumbnails()
                    if (missingAlbums.isNotEmpty()) {
                        processedAny = true
                        Log.d(TAG, "Found ${missingAlbums.size} albums missing thumbnails.")
                        for (album in missingAlbums) {
                            if (!isActive) break
                            
                            val page = Innertube.albumPage(album.id)?.getOrNull()
                            val thumbnailUrl = page?.thumbnail?.url
                            
                            if (thumbnailUrl != null) {
                                Database.update(album.copy(thumbnailUrl = thumbnailUrl))
                                Log.d(TAG, "Successfully synced album: ${album.title}")
                            } else {
                                Database.update(album.copy(thumbnailUrl = ""))
                                Log.d(TAG, "Thumbnail not found for album: ${album.title}")
                            }
                            
                            // Be gentle to the API to avoid rate limits
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing albums: ${e.message}")
                }

                // 2. Process Artists
                try {
                    val missingArtists = Database.artistsWithoutThumbnails()
                    if (missingArtists.isNotEmpty()) {
                        processedAny = true
                        Log.d(TAG, "Found ${missingArtists.size} artists missing thumbnails.")
                        for (artist in missingArtists) {
                            if (!isActive) break
                            
                            val page = Innertube.artistPage(artist.id)?.getOrNull()
                            val thumbnailUrl = page?.thumbnail?.url
                            
                            if (thumbnailUrl != null) {
                                Database.update(artist.copy(thumbnailUrl = thumbnailUrl))
                                Log.d(TAG, "Successfully synced artist: ${artist.name}")
                            } else {
                                Database.update(artist.copy(thumbnailUrl = ""))
                                Log.d(TAG, "Thumbnail not found for artist: ${artist.name}")
                            }
                            
                            // Be gentle to the API to avoid rate limits
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing artists: ${e.message}")
                }

                // If we didn't process anything, we are done for now.
                // Wait for an hour before checking again, just in case new media was added.
                if (!processedAny) {
                    Log.d(TAG, "All missing thumbnails synced. Sleeping for 1 hour.")
                    delay(3600_000L) // 1 hour
                } else {
                    // Small pause before fetching next batch
                    delay(2000)
                }
            }
        }
    }
}
