package com.github.musicyou.viewmodels

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.musicyou.models.Song
import com.github.musicyou.utils.formatAsDuration
import com.github.musicyou.utils.isAtLeastAndroid13
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VideoFolder(
    val name: String,
    val videos: List<Song>
)

class VideosViewModel(application: Application) : AndroidViewModel(application) {
    private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
    private val _allVideos = MutableStateFlow<List<Song>>(emptyList())
    private val _allMusic = MutableStateFlow<List<Song>>(emptyList())

    val searchQuery = MutableStateFlow("")

    val filteredVideoFolders = combine(_videoFolders, searchQuery) { folders, query ->
        if (query.isBlank()) {
            folders
        } else {
            folders.mapNotNull { folder ->
                val filteredVideos = folder.videos.filter { it.title.contains(query, ignoreCase = true) }
                if (folder.name.contains(query, ignoreCase = true) || filteredVideos.isNotEmpty()) {
                    folder.copy(videos = filteredVideos.ifEmpty { folder.videos })
                } else null
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAllVideos = combine(_allVideos, searchQuery) { videos, query ->
        if (query.isBlank()) videos else videos.filter { it.title.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAllMusic = combine(_allMusic, searchQuery) { music, query ->
        if (query.isBlank()) music else music.filter { 
            it.title.contains(query, ignoreCase = true) || 
            (it.artistsText?.contains(query, ignoreCase = true) == true) 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadMedia()
    }

    fun loadMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            
            // 1. Load Videos
            val videoCollection = if (isAtLeastAndroid13) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )

            val folderMap = mutableMapOf<String, MutableList<Song>>()
            val videosList = mutableListOf<Song>()

            try {
                context.contentResolver.query(
                    videoCollection,
                    videoProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val title = cursor.getString(titleColumn) ?: "Unknown Title"
                        val duration = cursor.getLong(durationColumn)
                        val bucketName = cursor.getString(bucketColumn) ?: "Unknown Folder"

                        val contentUri = ContentUris.withAppendedId(videoCollection, id)

                        val song = Song(
                            id = contentUri.toString(),
                            title = title,
                            artistsText = "Video",
                            durationText = formatAsDuration(duration),
                            thumbnailUrl = contentUri.toString() // Set to contentUri for Coil VideoFrameDecoder
                        )

                        videosList.add(song)

                        if (!folderMap.containsKey(bucketName)) {
                            folderMap[bucketName] = mutableListOf()
                        }
                        folderMap[bucketName]?.add(song)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            _allVideos.value = videosList
            _videoFolders.value = folderMap.map { VideoFolder(it.key, it.value) }.sortedBy { it.name }

            // 2. Load Audio
            val audioCollection = if (isAtLeastAndroid13) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val audioProjection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )

            val musicList = mutableListOf<Song>()

            try {
                context.contentResolver.query(
                    audioCollection,
                    audioProjection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val title = cursor.getString(titleColumn) ?: "Unknown Title"
                        val artist = cursor.getString(artistColumn)
                        val duration = cursor.getLong(durationColumn)
                        val albumId = cursor.getLong(albumIdColumn)

                        val contentUri = ContentUris.withAppendedId(audioCollection, id)
                        val artworkUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )

                        musicList.add(
                            Song(
                                id = contentUri.toString(),
                                title = title,
                                artistsText = if (artist == "<unknown>") "Unknown Artist" else artist,
                                durationText = formatAsDuration(duration),
                                thumbnailUrl = artworkUri.toString(),
                                likedAt = null
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            _allMusic.value = musicList
        }
    }
}
