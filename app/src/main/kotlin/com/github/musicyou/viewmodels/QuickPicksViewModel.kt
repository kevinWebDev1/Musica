package com.github.musicyou.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.github.innertube.Innertube
import com.github.innertube.requests.relatedPage
import com.github.innertube.requests.trending
import com.github.musicyou.Database
import com.github.musicyou.enums.QuickPicksSource
import com.github.musicyou.models.Song
import com.github.musicyou.utils.asMediaItem
import kotlinx.coroutines.flow.distinctUntilChanged
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.github.musicyou.utils.contentRegionKey
import com.github.musicyou.utils.favoriteGenresKey
import com.github.musicyou.utils.preferences
import kotlin.random.Random

class QuickPicksViewModel(application: Application) : AndroidViewModel(application) {
    var trending: Song? by mutableStateOf(null)
    var trendingSongs: List<Innertube.SongItem>? by mutableStateOf(null)
    var relatedPageResult: Result<Innertube.RelatedPage?>? by mutableStateOf(null)

    suspend fun loadQuickPicks(quickPicksSource: QuickPicksSource) {
        trendingSongs = null
        relatedPageResult = null
        val flow = when (quickPicksSource) {
            QuickPicksSource.Trending -> Database.trending()
            QuickPicksSource.LastPlayed -> Database.lastPlayed()
            QuickPicksSource.Random -> Database.randomSong()
        }

        flow.distinctUntilChanged().collect { song ->
            if (quickPicksSource == QuickPicksSource.Random && song != null && trending != null) return@collect

            if (song == null) {
                val context = getApplication<Application>()
                val region = context.preferences.getString(contentRegionKey, null)
                val genres = context.preferences.getString(favoriteGenresKey, null)
                    ?.split(",")?.filter { it.isNotEmpty() }
                
                val genre = genres?.let { if (it.isNotEmpty()) it[Random.nextInt(it.size)] else null }
                
                // Build a localized search query based on region and genre
                if (genre != null && trendingSongs == null) {
                    val regionLanguage = when (region) {
                        "IN" -> "Hindi"
                        "BR" -> "Portuguese"
                        "DE" -> "German"
                        "JP" -> "Japanese"
                        "KR" -> "Korean"
                        "FR" -> "French"
                        else -> null
                    }
                    val searchQuery = if (regionLanguage != null) {
                        "$genre $regionLanguage songs"
                    } else {
                        "$genre songs"
                    }
                    val searchResult = com.github.innertube.requests.searchSongs(searchQuery)?.getOrNull()
                    trendingSongs = searchResult?.items?.filterIsInstance<Innertube.SongItem>()
                }
                
                // Fall back to trending if search failed or no genre
                if (trendingSongs == null) {
                    trendingSongs = Innertube.trending(gl = region)?.getOrNull()
                }
                
                val fallbackSongId = trendingSongs?.let { songs ->
                    if (songs.isNotEmpty()) songs[Random.nextInt(songs.size)].key else null
                } ?: "fJ9rUzIMcZQ" // Hardcoded fallback if trending fetch fails
                
                if (relatedPageResult == null || relatedPageResult?.isSuccess != true) {
                    relatedPageResult = Innertube.relatedPage(videoId = fallbackSongId)
                }
            } else if (trending?.id != song.id || relatedPageResult?.isSuccess != true) {
                relatedPageResult = Innertube.relatedPage(videoId = song.id)
            }

            trending = song
        }
    }
}