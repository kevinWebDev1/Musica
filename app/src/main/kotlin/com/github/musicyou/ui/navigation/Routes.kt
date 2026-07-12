package com.github.musicyou.ui.navigation

import kotlinx.serialization.Serializable

sealed class Routes {
    @Serializable
    data object Home

    @Serializable
    data object Songs

    @Serializable
    data object Artists

    @Serializable
    data object Albums

    @Serializable
    data object Playlists

    @Serializable
    data object Videos

    @Serializable
    data class Artist(val id: String)

    @Serializable
    data class Album(val id: String)

    @Serializable
    data class Playlist(val id: String)

    @Serializable
    data object Settings

    @Serializable
    data class SettingsPage(val index: Int)

    @Serializable
    data object Search

    @Serializable
    data class BuiltInPlaylist(val index: Int)

    @Serializable
    data class LocalPlaylist(val id: Long)

    @Serializable
    data object Onboarding

    @Serializable
    data object Profile


    @Serializable
    data class FriendProfile(val id: String)

    @Serializable
    data class PublicPlaylist(val uid: String, val playlistId: String, val playlistName: String)

    @Serializable
    data object LocalFiles

    @Serializable
    data object FullscreenPlayer
}
