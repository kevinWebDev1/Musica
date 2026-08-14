package com.github.musicyou.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import com.github.musicyou.Database
import com.github.musicyou.enums.ArtistSortBy
import com.github.musicyou.enums.SortOrder
import com.github.musicyou.models.Artist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeArtistsViewModel : ViewModel() {
    var items: List<Artist> by mutableStateOf(emptyList())

    suspend fun loadArtists(
        sortBy: ArtistSortBy,
        sortOrder: SortOrder
    ) {
        Database
            .artists(sortBy, sortOrder)
            .collect { items = it }
    }

    suspend fun fetchMissingThumbnail(artist: Artist) {
        if (artist.thumbnailUrl != null) return
        withContext(Dispatchers.IO) {
            try {
                val page = Innertube.artistPage(artist.id)?.getOrNull()
                val thumbnailUrl = page?.thumbnail?.url
                println("MUSICA-LOG: Fetched artist ${artist.name} (${artist.id}) - thumbnail: $thumbnailUrl")
                if (thumbnailUrl != null) {
                    Database.update(artist.copy(thumbnailUrl = thumbnailUrl))
                } else {
                    println("MUSICA-LOG: Thumbnail was null for artist ${artist.name}. Page name: ${page?.name}")
                    Database.update(artist.copy(thumbnailUrl = ""))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Database.update(artist.copy(thumbnailUrl = ""))
            }
        }
    }
}