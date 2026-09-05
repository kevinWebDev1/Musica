package com.github.musicyou.utils

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.github.innertube.Innertube
import com.github.innertube.requests.player
import com.github.musicyou.Database
import com.github.musicyou.models.Format
import com.github.musicyou.service.LoginRequiredException
import com.github.musicyou.service.VideoIdMismatchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
object DownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    fun downloadSong(
        context: Context, 
        mediaItem: androidx.media3.common.MediaItem, 
        cacheDataSourceFactory: androidx.media3.datasource.cache.CacheDataSource.Factory
    ) {
        val videoId = mediaItem.mediaId
        scope.launch {
            try {
                // Show starting toast on Main Thread
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
                }

                // 1. Fetch the stream URL
                val responseResult = Innertube.player(videoId)
                
                if (responseResult == null || responseResult.isFailure) {
                    throw Exception("Failed to fetch stream")
                }
                
                val response = responseResult.getOrThrow()
                
                if (response.playabilityStatus?.status == "LOGIN_REQUIRED") {
                    throw LoginRequiredException()
                }
                
                if (response.videoDetails?.videoId != videoId) {
                    throw VideoIdMismatchException()
                }

                if (response.playabilityStatus?.status == "OK") {
                    // Try to get the highest quality audio stream
                    val audioFormat = response.streamingData?.adaptiveFormats
                        ?.filter { it.itag in listOf(251, 140, 250, 249) }
                        ?.maxByOrNull { 
                            when (it.itag) {
                                251 -> 4
                                140 -> 3
                                250 -> 2
                                249 -> 1
                                else -> 0
                            }
                        }

                    val url = audioFormat?.url ?: return@launch
                    
                    // 2. Create Cache Writer
                    val dataSpec = DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setKey(videoId) // Crucial: use videoId as the cache key so PlayerService finds it
                        .build()
                        
                    val cacheDataSource = cacheDataSourceFactory.createDataSource()
                    
                    val cacheWriter = CacheWriter(
                        cacheDataSource,
                        dataSpec,
                        null,
                        null
                    )
                    
                    // 3. Cache the stream
                    cacheWriter.cache()
                    
                    // 4. Insert into Database to show in Offline
                    Database.insert(mediaItem)
                    Database.insert(
                        Format(
                            songId = videoId,
                            itag = audioFormat.itag,
                            mimeType = audioFormat.mimeType,
                            bitrate = audioFormat.bitrate,
                            contentLength = audioFormat.contentLength,
                            lastModified = audioFormat.lastModified,
                            loudnessDb = response.playerConfig?.audioConfig?.normalizedLoudnessDb
                        )
                    )
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download complete", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Could not download this song", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
