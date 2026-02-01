package com.github.musicyou.utils

/**
 * Version comparison utilities for force update checks
 */
object VersionCheck {
    
    /**
     * Compare two semantic version strings (e.g., "2.2.0" vs "2.1.5")
     * @return true if currentVersion is older than requiredVersion
     */
    fun isVersionOlder(currentVersion: String, requiredVersion: String?): Boolean {
        if (requiredVersion == null) return false
        
        try {
            val current = parseVersion(currentVersion)
            val required = parseVersion(requiredVersion)
            
            // Compare major.minor.patch
            return when {
                current[0] < required[0] -> true  // Major version older
                current[0] > required[0] -> false // Major version newer
                current[1] < required[1] -> true  // Minor version older
                current[1] > required[1] -> false // Minor version newer
                current[2] < required[2] -> true  // Patch version older
                else -> false                      // Same or newer
            }
        } catch (e: Exception) {
            // If parsing fails, assume update not required
            android.util.Log.e("VersionCheck", "Failed to parse version: $e")
            return false
        }
    }
    
    /**
     * Parse version string into [major, minor, patch]
     */
    private fun parseVersion(version: String): List<Int> {
        return version.split(".")
            .take(3) // Only take first 3 parts
            .map { it.toIntOrNull() ?: 0 }
            .padEnd(3, 0) // Ensure we have 3 parts
    }
    
    /**
     * Pad list to specified size with default value
     */
    private fun <T> List<T>.padEnd(size: Int, default: T): List<T> {
        val result = this.toMutableList()
        while (result.size < size) {
            result.add(default)
        }
        return result
    }
}

/**
 * Update status levels
 */
enum class UpdateStatus {
    OK,                      // No update needed
    OPTIONAL,                // Update available, can skip
    RECOMMENDED,             // Update recommended, can dismiss
    FORCE_REQUIRED          // Must update to continue
}

/**
 * Version control configuration from Firebase
 */
data class VersionConfig(
    val currentVersion: String = "",
    val minimumRequiredVersion: String = "",
    val recommendedVersion: String = "",
    val forceUpdateEnabled: Boolean = false,
    val updateMessage: String = "A new version is available. Please update to continue using Musica.",
    val updateUrl: String = "https://kevinwebstore.vercel.app"
)
