package com.github.musicyou.providers.moviebox

import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object MovieBoxCrypto {
    private const val TAG = "[MOVIEBOX-CRYPTO]"

    val DEFAULT_SECRET_BYTES: ByteArray = byteArrayOf(
        0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
        0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
        0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(), 0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
        0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
    )

    private const val SIGNATURE_BODY_MAX_BYTES = 102_400
    const val STREAM_REFERER = "https://sportslive.wine"

    fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateXClientToken(ts: Long): String {
        val tsStr = ts.toString()
        val reversedTs = tsStr.reversed()
        val hashVal = md5Hex(reversedTs.toByteArray(Charsets.UTF_8))
        return "$tsStr,$hashVal"
    }

    fun sortedQueryString(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            val queryNames = uri.queryParameterNames
            if (queryNames.isEmpty()) return ""

            val sortedNames = queryNames.sorted()
            val parts = mutableListOf<String>()
            for (name in sortedNames) {
                val values = uri.getQueryParameters(name)
                for (v in values) {
                    parts.add("$name=$v")
                }
            }
            parts.joinToString("&")
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing query string from $urlStr", e)
            ""
        }
    }

    fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long
    ): String {
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val path = uri?.path ?: url
        val query = sortedQueryString(url)
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val (bodyHash, bodyLength) = if (bodyBytes != null) {
            val len = bodyBytes.size
            val truncated = if (len > SIGNATURE_BODY_MAX_BYTES) {
                bodyBytes.copyOfRange(0, SIGNATURE_BODY_MAX_BYTES)
            } else {
                bodyBytes
            }
            Pair(md5Hex(truncated), len.toString())
        } else {
            Pair("", "")
        }

        return "${method.uppercase()}\n${accept.orEmpty()}\n${contentType.orEmpty()}\n$bodyLength\n$timestampMs\n$bodyHash\n$canonicalUrl"
    }

    fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
        return try {
            val mac = Mac.getInstance("HmacMD5")
            val keySpec = SecretKeySpec(DEFAULT_SECRET_BYTES, "HmacMD5")
            mac.init(keySpec)
            val sigBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            val sigB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)
            "$timestampMs|2|$sigB64"
        } catch (e: Exception) {
            Log.e(TAG, "HMAC-MD5 generation failed", e)
            "$timestampMs|2|"
        }
    }

    fun randomSpoofedIp(): String {
        val prefixes = arrayOf(
            "103.241", "49.36", "117.195", "106.198", "122.162", "157.32", "182.70", "103.58", "27.60", "59.90"
        )
        val prefix = prefixes[Random.nextInt(prefixes.size)]
        val c = Random.nextInt(1, 254)
        val d = Random.nextInt(1, 254)
        return "$prefix.$c.$d"
    }

    fun generateClientInfoAndUa(): Pair<String, String> {
        val androidVersions = arrayOf(
            Pair("9", "PQ3A.190605.03081104"),
            Pair("10", "QP1A.191005.007.A3"),
            Pair("11", "RP1A.200720.011"),
            Pair("12", "S1B.220414.015"),
            Pair("13", "TQ2A.230405.003")
        )
        val devices = arrayOf(
            Pair("23078RKD5C", "Redmi"),
            Pair("2201117TY", "Redmi"),
            Pair("2201117TG", "Redmi"),
            Pair("22101316G", "Redmi"),
            Pair("21121210G", "Redmi"),
            Pair("M2012K11AG", "Redmi"),
            Pair("M2007J20CG", "Redmi")
        )
        val versionCodes = intArrayOf(50020117, 50020118, 50020119, 50020120, 50020121)
        val networks = arrayOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = arrayOf(
            "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "America/New_York", "Europe/London"
        )

        val android = androidVersions[Random.nextInt(androidVersions.size)]
        val device = devices[Random.nextInt(devices.size)]
        val versionCode = versionCodes[Random.nextInt(versionCodes.size)]
        val network = networks[Random.nextInt(networks.size)]
        val timezone = timezones[Random.nextInt(timezones.size)]
        val gaid = java.util.UUID.randomUUID().toString()
        val deviceId = (1..32).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

        val userAgent = "com.community.oneroom/$versionCode (Linux; U; Android ${android.first}; en_US; ${device.first}; Build/${android.second}; Cronet/135.0.7012.3)"

        val json = JSONObject().apply {
            put("package_name", "com.community.oneroom")
            put("version_name", "4.0.01.0813.03")
            put("version_code", versionCode)
            put("os", "android")
            put("os_version", android.first)
            put("install_ch", "ps")
            put("device_id", deviceId)
            put("install_store", "ps")
            put("gaid", gaid)
            put("brand", device.second)
            put("model", device.first)
            put("system_language", "en")
            put("net", network)
            put("region", "US")
            put("timezone", timezone)
            put("sp_code", "40401")
            put("X-Play-Mode", "2")
        }

        return Pair(userAgent, json.toString())
    }

    fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
                lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
                lower.contains("b164fbfb4347792950bdfbfb563d39d9") ||
                lower.contains("/notice.mp4") ||
                (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun decodeB64Padded(s: String): ByteArray? {
        var str = s
        val padding = (4 - str.length % 4) % 4
        if (padding in 1..3) {
            str += "=".repeat(padding)
        }
        return try {
            Base64.decode(str, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    fun resolveDashManifestFromPolicy(signCookie: String): String? {
        val parts = signCookie.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.contains("urlprefix=")) {
                val idx = trimmed.indexOf("urlprefix=")
                val prefixPart = trimmed.substring(idx + "urlprefix=".length)
                val b64Token = prefixPart.split(":").firstOrNull()?.trim() ?: prefixPart.trim()
                val normalized = b64Token.map { c ->
                    when (c) {
                        '-' -> '+'
                        '_' -> '/'
                        else -> c
                    }
                }.joinToString("")

                decodeB64Padded(normalized)?.let { decodedBytes ->
                    try {
                        val urlStr = String(decodedBytes, Charsets.UTF_8)
                        val mpd = toDashMpd(urlStr)
                        if (mpd != null) return mpd
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed decoding urlprefix bytes", e)
                    }
                }
            }

            if (trimmed.startsWith("CloudFront-Policy=")) {
                val policyRaw = trimmed.removePrefix("CloudFront-Policy=").trim()
                val normalized = policyRaw.map { c ->
                    when (c) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> c
                    }
                }.joinToString("")

                decodeB64Padded(normalized)?.let { decodedBytes ->
                    try {
                        val jsonStr = String(decodedBytes, Charsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        val statements = json.optJSONArray("Statement")
                        val resource = statements?.optJSONObject(0)?.optString("Resource")
                        if (!resource.isNullOrEmpty()) {
                            val mpd = toDashMpd(resource)
                            if (mpd != null) return mpd
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed decoding CloudFront policy", e)
                    }
                }
            }
        }
        return null
    }

    private fun toDashMpd(urlStr: String): String? {
        val base = urlStr.trimEnd('*', '/')
        return if (base.isNotEmpty() && (base.startsWith("http://") || base.startsWith("https://")) && base.contains("/dash/")) {
            "$base/index.mpd"
        } else {
            null
        }
    }

    fun cleanSignCookie(signCookie: String): String {
        return signCookie
            .trimEnd(';')
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
    }
}
