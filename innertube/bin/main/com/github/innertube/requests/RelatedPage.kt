package com.github.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.github.innertube.Innertube
import com.github.innertube.models.BrowseResponse
import com.github.innertube.models.MusicCarouselShelfRenderer
import com.github.innertube.models.NextResponse
import com.github.innertube.models.bodies.BrowseBody
import com.github.innertube.models.bodies.NextBody
import com.github.innertube.utils.findSectionByStrapline
import com.github.innertube.utils.findSectionByTitle
import com.github.innertube.utils.from
import com.github.innertube.utils.runCatchingNonCancellable

suspend fun Innertube.relatedPage(videoId: String) = runCatchingNonCancellable {
    val nextResponse = client.post(NEXT) {
        setBody(NextBody(videoId = videoId))
        mask("contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.tabRenderer(endpoint,title)")
    }.body<NextResponse>()

    val tabs = nextResponse
        .contents
        ?.singleColumnMusicWatchNextResultsRenderer
        ?.tabbedRenderer
        ?.watchNextTabbedResultsRenderer
        ?.tabs

    val browseId = tabs?.firstNotNullOfOrNull {
        it.tabRenderer?.endpoint?.browseEndpoint?.takeIf { browse ->
            browse.type == "MUSIC_PAGE_TYPE_TRACK_RELATED" || browse.browseId?.startsWith("MPTR") == true
        }?.browseId
    } ?: tabs?.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint?.browseId
    ?: return@runCatchingNonCancellable null

    val response = client.post(BROWSE) {
        setBody(
            BrowseBody(
                localized = false,
                browseId = browseId
            )
        )
        mask("contents")
    }.body<BrowseResponse>()

    val sectionListRenderer = response
        .contents
        ?.sectionListRenderer

    val contents = sectionListRenderer?.contents ?: emptyList()

    fun findSectionByKeywords(keywords: List<String>): com.github.innertube.models.SectionListRenderer.Content? {
        return contents.firstOrNull { content ->
            val title = content.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
            val strapline = content.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.strapline?.runs?.firstOrNull()?.text
            val text = "$title $strapline"
            keywords.any { text.contains(it, ignoreCase = true) }
        }
    }

    val songsSection = findSectionByKeywords(listOf("You might also like", "Songs", "Tracks", "Like")) ?: contents.getOrNull(0)
    val playlistsSection = findSectionByKeywords(listOf("Recommended playlists", "Playlists", "Mixes")) ?: contents.getOrNull(1)
    val albumsSection = findSectionByKeywords(listOf("MORE FROM", "Albums", "Singles", "Releases")) ?: contents.getOrNull(2)
    val artistsSection = findSectionByKeywords(listOf("Similar artists", "Artists", "Fans also like")) ?: contents.getOrNull(3)

    Innertube.RelatedPage(
        songs = songsSection
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from)
            ?.takeIf { it.isNotEmpty() },
        playlists = playlistsSection
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.PlaylistItem::from)
            ?.sortedByDescending { it.channel?.name == "YouTube Music" }
            ?.takeIf { it.isNotEmpty() },
        albums = albumsSection
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.AlbumItem::from)
            ?.takeIf { it.isNotEmpty() },
        artists = artistsSection
            ?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.ArtistItem::from)
            ?.takeIf { it.isNotEmpty() },
    )
}