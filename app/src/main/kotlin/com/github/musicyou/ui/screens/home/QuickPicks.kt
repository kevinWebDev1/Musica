package com.github.musicyou.ui.screens.home

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.innertube.Innertube
import com.github.innertube.models.NavigationEndpoint
import com.github.musicyou.Database
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.R
import com.github.musicyou.enums.QuickPicksSource
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.query
import com.github.musicyou.ui.components.HomeScaffold
import com.github.musicyou.ui.components.ShimmerHost
import com.github.musicyou.ui.components.NonQueuedMediaItemMenu
import com.github.musicyou.ui.components.TextPlaceholder
import com.github.musicyou.ui.items.AlbumItem
import com.github.musicyou.ui.items.ArtistItem
import com.github.musicyou.ui.items.ItemPlaceholder
import com.github.musicyou.ui.items.ListItemPlaceholder
import com.github.musicyou.ui.items.LocalSongItem
import com.github.musicyou.ui.items.PlaylistItem
import com.github.musicyou.ui.items.SongItem
import com.github.musicyou.ui.styling.Dimensions
import com.github.musicyou.utils.DisposableListener
import com.github.musicyou.utils.SnapLayoutInfoProvider
import com.github.musicyou.utils.asMediaItem
import com.github.musicyou.utils.forcePlay
import com.github.musicyou.utils.isLandscape
import com.github.musicyou.utils.onboardedKey
import com.github.musicyou.utils.quickPicksSourceKey
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.profileImageUrlKey
import com.github.musicyou.viewmodels.QuickPicksViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

@ExperimentalFoundationApi
@ExperimentalAnimationApi
@Composable
fun QuickPicks(
    openSearch: () -> Unit,
    openProfile: () -> Unit,
    openSettings: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onOfflinePlaylistClick: () -> Unit
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val playerPadding = LocalPlayerPadding.current

    val viewModel: QuickPicksViewModel = viewModel()
    val quickPicksSource by rememberPreference(quickPicksSourceKey, QuickPicksSource.Trending)
    val scope = rememberCoroutineScope()

    val songThumbnailSizeDp = Dimensions.thumbnails.song
    val itemSize = 108.dp + 2 * 8.dp
    val quickPicksLazyGridState = rememberLazyGridState()
    val sectionTextModifier = Modifier
        .padding(horizontal = 16.dp)
        .padding(bottom = 8.dp)

    val onboarded by rememberPreference(onboardedKey, defaultValue = false)
    val profileImageUrl = com.github.musicyou.utils.observePreference(profileImageUrlKey, defaultValue = "").value
    val profileImageLastUpdated = com.github.musicyou.utils.observePreference(com.github.musicyou.utils.profileImageLastUpdatedKey, defaultValue = 0L).value

    LaunchedEffect(quickPicksSource, onboarded) {
        viewModel.loadQuickPicks(quickPicksSource = quickPicksSource)
    }

    var currentMediaItem by remember { mutableStateOf(binder?.player?.currentMediaItem) }

    binder?.player?.let { player ->
        player.DisposableListener {
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentMediaItem = mediaItem
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                         currentMediaItem = player.currentMediaItem
                    }
                }
            }
        }
    }

    HomeScaffold(
        title = R.string.quick_picks,
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_icon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Musica",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        openSearch = openSearch,
        openProfile = openProfile,
        openSettings = openSettings,
        profileContent = {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(32.dp)
                    .clickable { openProfile() }
            ) {
                val finalPhotoUrl = remember(profileImageUrl, profileImageLastUpdated) {
                    if (profileImageUrl.isNotBlank() && profileImageLastUpdated > 0) {
                        "$profileImageUrl?ts=$profileImageLastUpdated"
                    } else {
                        profileImageUrl
                    }
                }

                if (finalPhotoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = finalPhotoUrl,
                        contentDescription = stringResource(R.string.profile),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                     Image(
                        painter = painterResource(id = R.drawable.app_icon), // Fallback
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
                
                // Online Status Indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color.Green, CircleShape)
                )
            }
        }
    ) {
        BoxWithConstraints {
            val quickPicksLazyGridItemWidthFactor =
                if (isLandscape && maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f

            val density = LocalDensity.current

            val snapLayoutInfoProvider = remember(quickPicksLazyGridState) {
                with(density) {
                    SnapLayoutInfoProvider(
                        lazyGridState = quickPicksLazyGridState,
                        positionInLayout = { layoutSize, itemSize ->
                            (layoutSize * quickPicksLazyGridItemWidthFactor / 2f - itemSize / 2f)
                        }
                    )
                }
            }

            val itemInHorizontalGridWidth = maxWidth * quickPicksLazyGridItemWidthFactor

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 16.dp + playerPadding)
            ) {
                // Determine what songs to show:
                // 1. If user has listening history (viewModel.trending != null), use relatedPage
                // 2. If no history but we have trendingSongs from search, show those directly
                val songsToDisplay = if (viewModel.trending != null) {
                    viewModel.relatedPageResult?.getOrNull()?.songs
                } else {
                    viewModel.trendingSongs
                }
                
                val relatedPageData = viewModel.relatedPageResult?.getOrNull()
                
                if (songsToDisplay != null && songsToDisplay.isNotEmpty()) {
                    LazyHorizontalGrid(
                        state = quickPicksLazyGridState,
                        rows = GridCells.Fixed(count = 4),
                        // removed snap behavior for better scroll sensitivity
                        modifier = Modifier
                             .fillMaxWidth()
                            .height((songThumbnailSizeDp + 16.dp + Dimensions.itemsVerticalPadding * 2) * 4),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        viewModel.trending?.let { song ->
                            item {
                                LocalSongItem(
                                    modifier = Modifier
                                        .animateItem()
                                        .width(itemInHorizontalGridWidth),
                                    song = song,
                                    isPlaying = song.id == currentMediaItem?.mediaId,
                                    onClick = {
                                        val mediaItem = song.asMediaItem
                                        binder?.stopRadio()
                                        binder?.player?.forcePlay(mediaItem)
                                        binder?.setupRadio(
                                            NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                        )
                                    },
                                    onLongClick = {
                                        menuState.display {
                                            NonQueuedMediaItemMenu(
                                                onDismiss = menuState::hide,
                                                mediaItem = song.asMediaItem,
                                                onRemoveFromQuickPicks = {
                                                    query {
                                                        Database.clearEventsFor(song.id)
                                                    }
                                                },
                                                onGoToAlbum = onAlbumClick,
                                                onGoToArtist = onArtistClick
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        items(
                            items = songsToDisplay.let { 
                                if (viewModel.trending != null) it.dropLast(1) else it 
                            },
                            key = Innertube.SongItem::key
                        ) { song ->
                            SongItem(
                                modifier = Modifier
                                    .animateItem()
                                    .width(itemInHorizontalGridWidth),
                                song = song,
                                isPlaying = song.key == currentMediaItem?.mediaId,
                                onClick = {
                                    val mediaItem = song.asMediaItem
                                    binder?.stopRadio()
                                    binder?.player?.forcePlay(mediaItem)
                                    binder?.setupRadio(
                                        NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                    )
                                },
                                onLongClick = {
                                    menuState.display {
                                        NonQueuedMediaItemMenu(
                                            onDismiss = menuState::hide,
                                            mediaItem = song.asMediaItem,
                                            onGoToAlbum = onAlbumClick,
                                            onGoToArtist = onArtistClick
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                    relatedPageData?.albums?.let { albums ->
                        Spacer(modifier = Modifier.height(Dimensions.spacer))

                        Text(
                            text = stringResource(id = R.string.related_albums),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = sectionTextModifier
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(
                                items = albums,
                                key = Innertube.AlbumItem::key
                            ) { album ->
                                AlbumItem(
                                    modifier = Modifier.widthIn(max = itemSize),
                                    album = album,
                                    onClick = { onAlbumClick(album.key) }
                                )
                            }
                        }
                    }

                    relatedPageData?.artists?.let { artists ->
                        Spacer(modifier = Modifier.height(Dimensions.spacer))

                        Text(
                            text = stringResource(id = R.string.similar_artists),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = sectionTextModifier
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(
                                items = artists,
                                key = Innertube.ArtistItem::key,
                            ) { artist ->
                                ArtistItem(
                                    modifier = Modifier.widthIn(max = itemSize),
                                    artist = artist,
                                    onClick = { onArtistClick(artist.key) }
                                )
                            }
                        }
                    }

                    relatedPageData?.playlists?.let { playlists ->
                        Spacer(modifier = Modifier.height(Dimensions.spacer))

                        Text(
                            text = stringResource(id = R.string.recommended_playlists),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = sectionTextModifier
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(
                                items = playlists,
                                key = Innertube.PlaylistItem::key,
                            ) { playlist ->
                                PlaylistItem(
                                    modifier = Modifier.widthIn(max = itemSize),
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.key) }
                                )
                            }
                        }
                    }

                    Unit
                }
                
                if (viewModel.relatedPageResult?.exceptionOrNull() != null && viewModel.trendingSongs.isNullOrEmpty()) {
                    Text(
                        text = stringResource(id = R.string.home_error),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.loadQuickPicks(quickPicksSource)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(id = R.string.retry)
                            )

                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))

                            Text(text = stringResource(id = R.string.retry))
                        }

                        FilledTonalButton(
                            onClick = onOfflinePlaylistClick
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DownloadForOffline,
                                contentDescription = stringResource(id = R.string.offline)
                            )

                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))

                            Text(text = stringResource(id = R.string.offline))
                        }
                    }
                }
                
                if (viewModel.relatedPageResult == null && viewModel.trendingSongs.isNullOrEmpty()) {
                    ShimmerHost {
                    TextPlaceholder(modifier = sectionTextModifier)

                    repeat(4) {
                        ListItemPlaceholder()
                    }

                    Spacer(modifier = Modifier.height(Dimensions.spacer))

                    TextPlaceholder(modifier = sectionTextModifier)

                    Row(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        repeat(2) {
                            ItemPlaceholder(modifier = Modifier.widthIn(max = itemSize))
                        }
                    }

                    Spacer(modifier = Modifier.height(Dimensions.spacer))

                    TextPlaceholder(modifier = sectionTextModifier)

                    Row(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        repeat(2) {
                            ItemPlaceholder(
                                modifier = Modifier.widthIn(max = itemSize),
                                shape = CircleShape
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Dimensions.spacer))

                    TextPlaceholder(modifier = sectionTextModifier)

                    Row(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        repeat(2) {
                            ItemPlaceholder(modifier = Modifier.widthIn(max = itemSize))
                        }
                    }
                }
            }
        }
    }
}
