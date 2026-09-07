package com.github.musicyou.updater

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WebstoreAppResponse(
    val success: Boolean,
    val data: WebstoreAppData? = null
)

@Serializable
data class WebstoreAppData(
    val id: String,
    val name: String,
    val version: String,
    val downloadUrl: String,
    val whatsNew: String
)

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val newVersion: String,
    val downloadUrl: String,
    val whatsNew: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val MUSICA_STORE_URL = "https://kevinwebstore.vercel.app/api/apps/musica"

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun checkForUpdates(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates at $MUSICA_STORE_URL")
            val response: WebstoreAppResponse = httpClient.get(MUSICA_STORE_URL).body()
            
            if (response.success && response.data != null) {
                val data = response.data
                val isNewer = isVersionNewer(currentVersionName, data.version)
                Log.d(TAG, "Current version: $currentVersionName, Remote version: ${data.version}, isNewer: $isNewer")
                
                if (isNewer) {
                    return@withContext UpdateInfo(
                        isUpdateAvailable = true,
                        newVersion = data.version,
                        downloadUrl = data.downloadUrl,
                        whatsNew = data.whatsNew
                    )
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            return@withContext null
        }
    }

    /**
     * Compares two semantic version strings (e.g. "3.1.0" vs "3.2.0")
     * Returns true if remoteVersion is strictly greater than currentVersion
     */
    private fun isVersionNewer(currentVersion: String, remoteVersion: String): Boolean {
        try {
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(currentParts.size, remoteParts.size)
            
            for (i in 0 until maxLength) {
                val c = currentParts.getOrElse(i) { 0 }
                val r = remoteParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false // Equal versions
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing versions for comparison", e)
            return false
        }
    }
}
