package com.github.musicyou.providers.moviebox

data class MovieBoxItem(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val year: String?,
    val posterUrl: String?,
    val description: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val seasonCount: Int = 0,
    val genres: List<String> = emptyList(),
    val maxQuality: String = "1080p"
)

data class MovieBoxSeason(
    val seasonNumber: Int,
    val maxEpisodes: Int,
    val episodes: List<MovieBoxEpisode> = emptyList()
)

data class MovieBoxEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null
)

data class MovieBoxStream(
    val id: String,
    val format: String,
    val resolution: String,
    val manifestUrl: String,
    val signCookie: String,
    val userAgent: String,
    val availableResolutions: List<String> = listOf("1080p", "720p", "480p"),
    val isDirectVideo: Boolean = false
)

data class MovieBoxSubtitle(
    val language: String,
    val url: String
)
