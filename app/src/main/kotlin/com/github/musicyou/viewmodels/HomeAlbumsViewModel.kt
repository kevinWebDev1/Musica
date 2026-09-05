package com.github.musicyou.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.github.innertube.Innertube
import com.github.innertube.requests.albumPage
import com.github.musicyou.Database
import com.github.musicyou.enums.AlbumSortBy
import com.github.musicyou.enums.SortOrder
import com.github.musicyou.models.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeAlbumsViewModel : ViewModel() {
    var items: List<Album> by mutableStateOf(emptyList())

    suspend fun loadAlbums(
        sortBy: AlbumSortBy,
        sortOrder: SortOrder
    ) {
        Database
            .albums(sortBy, sortOrder)
            .collect { items = it }
    }

    suspend fun fetchMissingThumbnail(album: Album) {
        if (!album.thumbnailUrl.isNullOrEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val page = Innertube.albumPage(album.id)?.getOrNull()
                val thumbnailUrl = page?.thumbnail?.url
                println("MUSICA-LOG: Fetched album ${album.title} (${album.id}) - thumbnail: $thumbnailUrl")
                if (thumbnailUrl != null) {
                    Database.update(album.copy(thumbnailUrl = thumbnailUrl))
                } else {
                    println("MUSICA-LOG: Thumbnail was null for album ${album.title}. Page title: ${page?.title}")
                    Database.update(album.copy(thumbnailUrl = ""))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Database.update(album.copy(thumbnailUrl = ""))
            }
        }
    }
}