package com.github.musicyou.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import com.github.musicyou.R

object TopDestinations {
    val list = listOf(
        TopDestination(
            route = Routes.Home,
            resourceId = R.string.home,
            unselectedIcon = Icons.Outlined.Home,
            selectedIcon = Icons.Filled.Home
        ),
        TopDestination(
            route = Routes.Songs,
            resourceId = R.string.songs,
            unselectedIcon = Icons.Outlined.MusicNote,
            selectedIcon = Icons.Filled.MusicNote
        ),
        TopDestination(
            route = Routes.Videos,
            resourceId = R.string.videos,
            unselectedIcon = Icons.Outlined.VideoLibrary,
            selectedIcon = Icons.Filled.VideoLibrary
        ),
        TopDestination(
            route = Routes.Artists,
            resourceId = R.string.artists,
            unselectedIcon = Icons.Outlined.Person,
            selectedIcon = Icons.Filled.Person
        ),
        TopDestination(
            route = Routes.Playlists,
            resourceId = R.string.playlists,
            unselectedIcon = Icons.AutoMirrored.Outlined.QueueMusic,
            selectedIcon = Icons.AutoMirrored.Filled.QueueMusic
        )
    )

    val routes = list.map { it.route }
}