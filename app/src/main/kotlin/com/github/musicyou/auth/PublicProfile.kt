package com.github.musicyou.auth

/**
 * Public-facing user profile data.
 * Stored in Firestore for permanent, queryable access.
 * 
 * Distinction from Presence:
 * - Profile = Stable data (changes rarely)
 * - Presence = Volatile data (changes frequently)
 */
data class PublicProfile(
    val uid: String,
    val displayName: String,
    val username: String,
    val photoUrl: String? = null,
    val country: String? = null,
    val favoriteGenres: List<String> = emptyList(),
    val bio: String? = null
)
