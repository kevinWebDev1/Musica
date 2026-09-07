package com.github.musicyou.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

object LrcLib {

    @Serializable
    data class LrcLibResponse(
        val id: Int? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )

    private val client by lazy {
        HttpClient(OkHttp) {
            BrowserUserAgent()
            expectSuccess = false

            install(ContentNegotiation) {
                val feature = Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                }
                json(feature)
                json(feature, ContentType.Text.Html)
                json(feature, ContentType.Text.Plain)
            }

            defaultRequest {
                url("https://lrclib.net")
            }
        }
    }

    suspend fun lyrics(artist: String, title: String, duration: Long): Result<String?> {
        return runCatching {
            val response = client.get("/api/search") {
                parameter("track_name", title)
                parameter("artist_name", artist)
            }

            if (response.status.value !in 200..299) return@runCatching null

            val candidates = try {
                response.body<List<LrcLibResponse>>()
            } catch (e: Exception) {
                emptyList<LrcLibResponse>()
            }

            if (candidates.isEmpty()) return@runCatching null

            var bestMatch: String? = null
            var minDiff = Double.MAX_VALUE

            for (candidate in candidates) {
                if (!candidate.syncedLyrics.isNullOrBlank()) {
                    if (candidate.duration != null) {
                        val diff = abs(candidate.duration - duration)
                        if (diff <= 15.0 && diff < minDiff) {
                            minDiff = diff
                            bestMatch = candidate.syncedLyrics
                        }
                    } else if (bestMatch == null) {
                        bestMatch = candidate.syncedLyrics
                    }
                }
            }

            if (bestMatch == null) {
                bestMatch = candidates.firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.syncedLyrics
            }

            bestMatch
        }
    }
}
