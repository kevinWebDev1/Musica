package com.github.musicyou.providers.moviebox

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object MovieBoxClient {
    private const val TAG = "[MOVIEBOX-API]"

    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    private val activeHostIndex = AtomicInteger(0)
    private val sessionMutex = Mutex()

    private var cachedToken: String? = null
    private var cachedUserId: String? = null
    private var tokenExpiryEpochSec: Long = 0

    private val clientInfoAndUa by lazy { MovieBoxCrypto.generateClientInfoAndUa() }
    val userAgent: String get() = clientInfoAndUa.first
    val clientInfoJson: String get() = clientInfoAndUa.second
    val spoofedIp: String = MovieBoxCrypto.randomSpoofedIp()

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private fun isSessionValid(): Boolean {
        val token = cachedToken ?: return false
        if (token.isBlank()) return false
        val nowSec = System.currentTimeMillis() / 1000
        return nowSec + 60 < tokenExpiryEpochSec
    }

    suspend fun ensureSession(): String = sessionMutex.withLock {
        if (isSessionValid()) {
            return@withLock cachedToken!!
        }

        Log.i(TAG, "Requesting fresh visitor session from MovieBox...")
        val responseJson = requestHostsInternal(
            method = "POST",
            pathAndQuery = "/wefeed-mobile-bff/user-api/visitor-login",
            body = "{}",
            authToken = null
        )

        val data = responseJson.optJSONObject("data") ?: responseJson
        val token = data.optString("token")
        if (token.isNullOrEmpty()) {
            throw IllegalStateException("Failed to obtain visitor token: $responseJson")
        }

        cachedToken = token
        cachedUserId = data.optString("userId").ifEmpty { data.optString("uid") }
        tokenExpiryEpochSec = (System.currentTimeMillis() / 1000) + (7 * 24 * 3600)

        // Parse JWT expiry if available
        try {
            val parts = token.split(".")
            if (parts.size > 1) {
                var payloadB64 = parts[1]
                val pad = (4 - (payloadB64.length % 4)) % 4
                if (pad in 1..3) payloadB64 += "=".repeat(pad)
                val decoded = android.util.Base64.decode(payloadB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                val jwtJson = JSONObject(String(decoded, Charsets.UTF_8))
                val exp = jwtJson.optLong("exp", 0)
                if (exp > 0) tokenExpiryEpochSec = exp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing JWT claims", e)
        }

        Log.i(TAG, "Visitor session obtained successfully. Token: ${token.take(15)}..., UserId: $cachedUserId")
        token
    }

    private suspend fun requestHosts(
        method: String,
        pathAndQuery: String,
        body: String? = null
    ): JSONObject {
        val token = ensureSession()
        return try {
            requestHostsInternal(method, pathAndQuery, body, token)
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                Log.w(TAG, "Token expired or rejected. Refreshing session and retrying...", e)
                sessionMutex.withLock { cachedToken = null }
                val freshToken = ensureSession()
                requestHostsInternal(method, pathAndQuery, body, freshToken)
            } else {
                throw e
            }
        }
    }

    private suspend fun requestHostsInternal(
        method: String,
        pathAndQuery: String,
        body: String?,
        authToken: String?
    ): JSONObject = withContext(Dispatchers.IO) {
        val startIdx = activeHostIndex.get()
        var lastException: Exception? = null

        for (i in HOST_POOL.indices) {
            val idx = (startIdx + i) % HOST_POOL.size
            val host = HOST_POOL[idx]
            val fullUrl = "$host$pathAndQuery"

            try {
                val now = System.currentTimeMillis()
                val clientToken = MovieBoxCrypto.generateXClientToken(now)
                val signature = MovieBoxCrypto.generateXTrSignature(
                    method = method,
                    accept = "application/json",
                    contentType = "application/json",
                    url = fullUrl,
                    body = body,
                    timestampMs = now
                )

                val response: HttpResponse = client.request(fullUrl) {
                    this.method = HttpMethod.parse(method)
                    header("User-Agent", userAgent)
                    header("Accept", "application/json")
                    header("Content-Type", "application/json")
                    header("Connection", "keep-alive")
                    header("x-client-token", clientToken)
                    header("x-tr-signature", signature)
                    header("x-client-info", clientInfoJson)
                    header("x-client-status", "0")
                    header("x-forwarded-for", spoofedIp)

                    if (!authToken.isNullOrEmpty()) {
                        header("Authorization", "Bearer $authToken")
                    }

                    if (body != null) {
                        setBody(body)
                    }
                }

                // Check for x-user token renewal
                response.headers["x-user"]?.let { xUserStr ->
                    try {
                        val xUserJson = JSONObject(xUserStr)
                        val renewedToken = xUserJson.optString("token")
                        if (renewedToken.isNotEmpty()) {
                            cachedToken = renewedToken
                        }
                    } catch (_: Exception) {}
                }

                val status = response.status
                if (status == HttpStatusCode.OK) {
                    activeHostIndex.set(idx)
                    val text = response.bodyAsText()
                    return@withContext JSONObject(text)
                }

                if (status.value in listOf(403, 406, 407, 429, 500, 502, 503)) {
                    Log.w(TAG, "Host $host returned retryable status ${status.value}, trying next host...")
                    activeHostIndex.set((idx + 1) % HOST_POOL.size)
                    continue
                }

                throw IllegalStateException("API error: HTTP ${status.value} from $fullUrl")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Host $host failed: ${e.message}, trying next host...")
                activeHostIndex.set((idx + 1) % HOST_POOL.size)
            }
        }

        throw lastException ?: IllegalStateException("All MovieBox API hosts exhausted")
    }

    suspend fun search(query: String, page: Int = 1): Result<List<MovieBoxItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("keyword", query)
                put("page", page)
                put("perPage", 20)
                put("subjectType", 0)
            }

            val json = requestHosts("POST", "/wefeed-mobile-bff/subject-api/search/v2", payload.toString())
            val resultsArr = json.optJSONObject("data")?.optJSONArray("results") ?: json.optJSONArray("results")
            val items = mutableListOf<MovieBoxItem>()

            if (resultsArr != null && resultsArr.length() > 0) {
                val firstGroup = resultsArr.optJSONObject(0)
                val subjects = firstGroup?.optJSONArray("subjects")
                if (subjects != null) {
                    for (i in 0 until subjects.length()) {
                        val s = subjects.optJSONObject(i) ?: continue
                        parseSubjectItem(s)?.let { items.add(it) }
                    }
                }
            }

            // Also check data.list fallback
            if (items.isEmpty()) {
                val listArr = json.optJSONObject("data")?.optJSONArray("list") ?: json.optJSONArray("list")
                if (listArr != null) {
                    for (i in 0 until listArr.length()) {
                        val s = listArr.optJSONObject(i) ?: continue
                        parseSubjectItem(s)?.let { items.add(it) }
                    }
                }
            }

            items
        }
    }

    suspend fun getTrending(page: Int = 1): Result<List<MovieBoxItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/wefeed-mobile-bff/tab-operating?page=$page&tabId=1&version="
            val json = requestHosts("GET", path)
            val data = json.optJSONObject("data") ?: json
            val groups = data.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<MovieBoxItem>()
            val seenIds = mutableSetOf<String>()

            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue

                // Check banners
                val banners = group.optJSONObject("banner")?.optJSONArray("banners")
                if (banners != null) {
                    for (j in 0 until banners.length()) {
                        val b = banners.optJSONObject(j)?.optJSONObject("subject") ?: continue
                        parseSubjectItem(b)?.let { item ->
                            if (seenIds.add(item.id)) items.add(item)
                        }
                    }
                }

                // Check customData
                val customItems = group.optJSONObject("customData")?.optJSONArray("items")
                if (customItems != null) {
                    for (j in 0 until customItems.length()) {
                        val c = customItems.optJSONObject(j)?.optJSONObject("subject") ?: continue
                        parseSubjectItem(c)?.let { item ->
                            if (seenIds.add(item.id)) items.add(item)
                        }
                    }
                }

                // Check direct subjects
                val subjects = group.optJSONArray("subjects")
                if (subjects != null) {
                    for (j in 0 until subjects.length()) {
                        val s = subjects.optJSONObject(j) ?: continue
                        parseSubjectItem(s)?.let { item ->
                            if (seenIds.add(item.id)) items.add(item)
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                // Fallback to top movie searches if tab-operating is sparse
                return@runCatching search("2026", 1).getOrDefault(emptyList())
            }

            items
        }
    }

    suspend fun getDetails(subjectId: String): Result<Pair<MovieBoxItem, List<MovieBoxSeason>>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
            val json = requestHosts("GET", path)
            val data = json.optJSONObject("data") ?: json
            val subject = data.optJSONObject("subject") ?: data

            val item = parseSubjectItem(subject) ?: throw IllegalStateException("Invalid subject details")

            val seasons = mutableListOf<MovieBoxSeason>()
            if (item.isSeries) {
                try {
                    val seasonPath = "/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                    val seasonJson = requestHosts("GET", seasonPath)
                    val seasonData = seasonJson.optJSONObject("data") ?: seasonJson
                    val seasonsArr = seasonData.optJSONArray("seasons")
                    if (seasonsArr != null) {
                        for (i in 0 until seasonsArr.length()) {
                            val s = seasonsArr.optJSONObject(i) ?: continue
                            val seNum = s.optInt("se", i + 1)
                            val maxEp = s.optInt("maxEp", 1)
                            val episodes = (1..maxEp).map { epNum ->
                                MovieBoxEpisode(
                                    seasonNumber = seNum,
                                    episodeNumber = epNum,
                                    title = "Episode $epNum"
                                )
                            }
                            seasons.add(MovieBoxSeason(seNum, maxEp, episodes))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching season-info for $subjectId", e)
                }
            }

            Pair(item, seasons)
        }
    }

    suspend fun getStreams(
        subjectId: String,
        season: Int = 0,
        episode: Int = 0
    ): Result<List<MovieBoxStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = if (season == 0 && episode == 0) {
                "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$subjectId"
            } else {
                "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$subjectId&se=$season&ep=$episode"
            }

            val json = requestHosts("GET", path)
            val data = json.optJSONObject("data") ?: json
            val streamsArr = data.optJSONArray("streams") ?: JSONArray()
            val streams = mutableListOf<MovieBoxStream>()

            for (i in 0 until streamsArr.length()) {
                val streamObj = streamsArr.optJSONObject(i) ?: continue
                val streamId = streamObj.optString("id", i.toString())
                val format = streamObj.optString("format", "MP4")
                val resolutions = streamObj.optString("resolutions", "1080")
                val signCookie = streamObj.optString("signCookie", "")
                val rawUrl = streamObj.optString("url", "")

                // Resolve true playable manifest from signCookie policy
                val manifestUrl = MovieBoxCrypto.resolveDashManifestFromPolicy(signCookie)
                    ?: if (!MovieBoxCrypto.isDeprecationNoticeUrl(rawUrl) && rawUrl.startsWith("http")) {
                        rawUrl
                    } else null

                if (manifestUrl != null) {
                    val cleanCookie = MovieBoxCrypto.cleanSignCookie(signCookie)
                    val rawResStr = streamObj.optString("resolutions").ifEmpty {
                        data.optString("displayResolutions", "1080")
                    }
                    val resList = rawResStr.split(",")
                        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                        .map { if (it.endsWith("p", ignoreCase = true)) it else "${it}p" }
                        .distinct()
                    val mainRes = resList.firstOrNull() ?: "1080p"
                    val availableRes = if (resList.isEmpty()) listOf("1080p", "720p", "480p") else resList

                    streams.add(
                        MovieBoxStream(
                            id = streamId,
                            format = format,
                            resolution = mainRes,
                            manifestUrl = manifestUrl,
                            signCookie = cleanCookie,
                            userAgent = userAgent,
                            availableResolutions = availableRes,
                            isDirectVideo = !manifestUrl.endsWith(".mpd")
                        )
                    )
                }
            }

            if (streams.isEmpty()) {
                throw IllegalStateException("No active video streams found for $subjectId (S${season}E${episode})")
            }

            streams
        }
    }

    suspend fun getSubtitles(
        subjectId: String,
        resourceId: String
    ): Result<List<MovieBoxSubtitle>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$resourceId"
            val json = requestHosts("GET", path)
            val data = json.optJSONObject("data") ?: json
            val captions = data.optJSONArray("extCaptions") ?: JSONArray()
            val subtitles = mutableListOf<MovieBoxSubtitle>()

            for (i in 0 until captions.length()) {
                val cap = captions.optJSONObject(i) ?: continue
                val url = cap.optString("url")
                if (url.isEmpty() || url.contains("aa348f2541d13ffe")) continue
                val lanName = cap.optString("lanName").ifEmpty { cap.optString("lan", "Unknown") }
                subtitles.add(MovieBoxSubtitle(language = lanName, url = url))
            }

            subtitles
        }
    }

    private fun parseSubjectItem(s: JSONObject): MovieBoxItem? {
        val id = s.optString("subjectId").ifEmpty { s.optString("id") }
        if (id.isEmpty()) return null

        val title = s.optString("title").ifEmpty { s.optString("name", "Unknown") }
        val stype = s.optInt("subjectType", s.optInt("stype", 1))
        val isSeries = stype == 2

        val releaseDate = s.optString("releaseDate").ifEmpty { s.optString("year") }
        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null

        val posterUrl = s.optJSONObject("cover")?.optString("url")
            ?: s.optString("coverUrl").ifEmpty {
                s.optString("poster").ifEmpty { s.optString("pic") }
            }.takeIf { it.isNotEmpty() }

        val desc = s.optString("description").ifEmpty { s.optString("intro") }.takeIf { it.isNotEmpty() }
        val rating = s.optString("imdbRatingValue").ifEmpty {
            val r = s.optDouble("rating", 0.0)
            if (r > 0) "%.1f".format(r) else null
        }
        val duration = s.optInt("duration", 0).let { if (it > 0) "${it / 60}m" else null }
        val seasonCount = s.optInt("season", 0)

        val genres = mutableListOf<String>()
        val genreArr = s.optJSONArray("genre") ?: s.optJSONArray("genres")
        if (genreArr != null) {
            for (i in 0 until genreArr.length()) {
                val g = genreArr.optString(i)
                if (g.isNotEmpty()) genres.add(g)
            }
        }

        val isCam = s.optBoolean("isCam", false)
        val corner = s.optString("corner", "")
        val maxQuality = if (isCam || corner.contains("CAM", ignoreCase = true) || corner.contains("TC", ignoreCase = true) || corner.contains("TS", ignoreCase = true)) {
            "CAM"
        } else {
            val resStr = s.optString("displayResolutions").ifEmpty {
                s.optString("resolutions").ifEmpty {
                    s.optString("maxResolution", "").ifEmpty {
                        corner
                    }
                }
            }
            when {
                resStr.contains("4K", ignoreCase = true) || resStr.contains("2160") -> "4K"
                resStr.contains("1080") -> "1080p"
                resStr.contains("720") -> "720p"
                resStr.contains("480") -> "480p"
                else -> "1080p"
            }
        }

        return MovieBoxItem(
            id = id,
            title = title,
            isSeries = isSeries,
            year = year,
            posterUrl = posterUrl,
            description = desc,
            rating = rating,
            duration = duration,
            seasonCount = seasonCount,
            genres = genres,
            maxQuality = maxQuality
        )
    }
}
