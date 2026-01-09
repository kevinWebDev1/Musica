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
        mask("contents.sectionListRenderer.contents.musicCarouselShelfRenderer(header.musicCarouselShelfBasicHeaderRenderer(title),contents($MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK))")
    }.body<BrowseResponse>()

    val sectionListRendererContent = response.contents?.sectionListRenderer
        ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer

    sectionListRendererContent
        ?.findSectionByTitle("Top songs") // Standard English title
        ?.musicCarouselShelfRenderer
        ?.contents
        ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
        ?.mapNotNull(Innertube.SongItem::from)
        ?: sectionListRendererContent
            ?.findSectionByTitle("Top music videos") // Often prominently regional
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from)
        ?: sectionListRendererContent
            ?.findSectionByTitle("Trending") 
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from)
        ?: sectionListRendererContent
            ?.contents
            ?.filter { it.musicCarouselShelfRenderer != null || it.musicShelfRenderer != null }
            ?.firstOrNull()
            ?.let { content ->
                (content.musicCarouselShelfRenderer ?: content.musicShelfRenderer as? MusicCarouselShelfRenderer)
                    ?.contents
                    ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
                    ?.mapNotNull(Innertube.SongItem::from)
            }
}
