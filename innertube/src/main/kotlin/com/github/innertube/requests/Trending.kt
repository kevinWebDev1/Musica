package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.github.innertube.Innertube
import com.github.innertube.models.BrowseResponse
import com.github.innertube.models.MusicCarouselShelfRenderer
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.BrowseBody
import com.github.innertube.utils.findSectionByTitle
import com.github.innertube.utils.from
import com.github.innertube.utils.runCatchingNonCancellable

/**
 * Fetches trending songs from YouTube Music charts/trending page.
 * Uses localized context to return country-specific results.
 */
suspend fun Innertube.trending(
    gl: String? = null,
    hl: String? = null,
    genre: String? = null
) = runCatchingNonCancellable {
    val response = client.post(BROWSE) {
        setBody(
            BrowseBody(
                browseId = if (genre != null) "FEmusic_genre_selection_$genre" else "FEmusic_charts",
                context = YouTubeClient.WEB_REMIX.toContext(gl = gl, hl = hl)
            )
        )
        mask("contents")
    }.body<BrowseResponse>()

    val sectionListRendererContent = response.contents?.sectionListRenderer
        ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer

    val sections = sectionListRendererContent?.contents ?: emptyList()

    // 1. Try to find section matching known title keywords
    val knownTitles = listOf("Top songs", "Top music videos", "Trending", "Charts", "Popular")
    val matchedSection = sections.firstOrNull { content ->
        val title = content.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
            ?: content.musicShelfRenderer?.title?.runs?.firstOrNull()?.text
        title != null && knownTitles.any { known -> title.contains(known, ignoreCase = true) }
    } ?: sections.firstOrNull { it.musicCarouselShelfRenderer != null || it.musicShelfRenderer != null }

    val itemsFromCarousel = matchedSection?.musicCarouselShelfRenderer?.contents
        ?.mapNotNull { it.musicResponsiveListItemRenderer }
        ?.mapNotNull { Innertube.SongItem.from(it) }

    val itemsFromShelf = matchedSection?.musicShelfRenderer?.contents
        ?.mapNotNull { it.musicResponsiveListItemRenderer }
        ?.mapNotNull { Innertube.SongItem.from(it) }

    itemsFromCarousel ?: itemsFromShelf ?: sections.firstNotNullOfOrNull { content ->
        content.musicCarouselShelfRenderer?.contents
            ?.mapNotNull { it.musicResponsiveListItemRenderer }
            ?.mapNotNull { Innertube.SongItem.from(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: content.musicShelfRenderer?.contents
                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                ?.mapNotNull { Innertube.SongItem.from(it) }
                ?.takeIf { it.isNotEmpty() }
    }
}
