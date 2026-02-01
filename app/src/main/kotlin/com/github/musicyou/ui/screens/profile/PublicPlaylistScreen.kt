package com.github.musicyou.ui.screens.profile

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.R
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.models.ActionInfo
import com.github.musicyou.ui.components.CoverScaffold
import com.github.musicyou.ui.components.PlaylistThumbnail
import com.github.musicyou.ui.items.LocalSongItem
import com.github.musicyou.utils.enqueue
import com.github.musicyou.utils.forcePlayAtIndex
import com.github.musicyou.utils.forcePlayFromBeginning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun PublicPlaylistScreen(
    uid: String,
    playlistId: String,
    playlistName: String,
    pop: () -> Unit
) {
    val binder = LocalPlayerServiceBinder.current
    val playerPadding = LocalPlayerPadding.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    var songs by remember { mutableStateOf<List<ProfileManager.PlaylistSong>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(uid, playlistId) {
        val result = if (playlistId == "favorites") {
            ProfileManager.getPublicLikedSongs(uid)
        } else {
            ProfileManager.getPublicPlaylistSongs(uid, playlistId)
        }

        result.onSuccess {
            songs = it
            isLoading = false
        }.onFailure {
            isLoading = false
        }
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        text = playlistName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = pop) {
                        Icon(
                             imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                             contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + playerPadding),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                 item(key = "controls") {
                     Row(
                         modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                         horizontalArrangement = Arrangement.SpaceEvenly
                     ) {
                         Button(
                             onClick = {
                                 if (songs.isNotEmpty()) {
                                     binder?.stopRadio()
                                     binder?.player?.forcePlayFromBeginning(
                                         songs.shuffled().map { it.asMediaItem() }
                                     )
                                 }
                             },
                             enabled = songs.isNotEmpty()
                         ) {
                             Icon(Icons.Outlined.Shuffle, contentDescription = null)
                             Spacer(modifier = Modifier.width(8.dp))
                             Text("Shuffle")
                         }
                         
                         Button(
                             onClick = {
                                 if (songs.isNotEmpty()) {
                                     binder?.stopRadio()
                                     binder?.player?.forcePlayFromBeginning(
                                         songs.map { it.asMediaItem() }
                                     )
                                 }
                             },
                             enabled = songs.isNotEmpty()
                         ) {
                             Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null)
                             Spacer(modifier = Modifier.width(8.dp))
                             Text("Play")
                         }
                     }
                 }

                item(key = "spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.mediaId }
                ) { index, song ->
                    // Using a simplified Song item representation
                    ListItem(
                        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Text((index + 1).toString())
                        },
                        modifier = Modifier.clickable {
                            binder?.stopRadio()
                            binder?.player?.forcePlayAtIndex(
                                songs.map { it.asMediaItem() },
                                index
                            )
                        }
                    )
                }
            }
        }
    }
}
