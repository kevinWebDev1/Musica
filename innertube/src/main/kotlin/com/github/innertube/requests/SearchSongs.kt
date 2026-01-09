package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.github.innertube.Innertube
import com.github.innertube.models.SearchResponse
import com.github.innertube.models.MusicShelfRenderer
import com.github.innertube.models.bodies.SearchBody
import com.github.innertube.utils.from
import com.github.innertube.utils.runCatchingNonCancellable

/**
 * Searches for songs matching the given query.
 * Returns a list of SongItems.
 */
suspend fun searchSongs(query: String) = runCatchingNonCancellable {
    val response = Innertube.client.post(Innertube.SEARCH) {
        setBody(
            SearchBody(
                query = query,
                params = Innertube.SearchFilter.Song.value
            )
        )
        Innertube.run { mask("contents.tabbedSearchResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents.musicShelfRenderer(continuations,contents.${MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK})") }
    }.body<SearchResponse>()

    val musicShelfRenderer = response
        .contents
        ?.tabbedSearchResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        ?.lastOrNull()
        ?.musicShelfRenderer

    Innertube.ItemsPage(
        items = musicShelfRenderer
            ?.contents
            ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from),
        continuation = musicShelfRenderer
            ?.continuations
            ?.firstOrNull()
            ?.nextContinuationData
            ?.continuation
    )
}
