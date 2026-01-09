package com.github.musicyou.utils

object VersionUtils {
    /**
     * Compares two version strings.
     * Returns true if latest version is strictly newer than current version.
     * Handles versions like "1.2.3", "1.10.0", "v1.2.0".
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(latestParts.size, currentParts.size)
        
        for (i in 0 until maxLength) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        
        return false
    }
}
