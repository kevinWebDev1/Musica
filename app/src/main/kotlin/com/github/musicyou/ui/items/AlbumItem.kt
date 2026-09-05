package com.github.musicyou.ui.items

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import com.github.innertube.Innertube
import com.github.musicyou.models.Album
import com.github.musicyou.ui.components.ShimmerHost
import com.github.musicyou.ui.styling.px
import com.github.musicyou.utils.thumbnail

@Composable
fun AlbumItem(
    modifier: Modifier = Modifier,
    album: Innertube.AlbumItem,
    onClick: () -> Unit
) {
    ItemContainer(
        modifier = modifier,
        title = album.info?.name ?: "",
        subtitle = if (album.authors.isNullOrEmpty()) album.year
        else "${album.authors?.joinToString(separator = "") { it.name ?: "" }} • ${album.year}",
        onClick = onClick,
        fullCard = true
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            val itemSize = maxWidth
            SubcomposeAsyncImage(
                model = album.thumbnail?.url?.thumbnail(itemSize.px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large),
                loading = {
                    ShimmerHost(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                },
                error = {
                    LaunchedEffect(Unit) {
                        Log.d("AlbumItem", "[DEBUG-ALBUM] Image load failed for Innertube Album: ${album.info?.name} url: ${album.thumbnail?.url}")
                    }
                    ShimmerHost(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun LocalAlbumItem(
    modifier: Modifier = Modifier,
    album: Album,
    onClick: () -> Unit
) {
    ItemContainer(
        modifier = modifier,
        title = album.title ?: "",
        subtitle = if (album.authorsText.isNullOrEmpty()) album.year
        else "${album.authorsText} • ${album.year}",
        onClick = onClick,
        fullCard = true
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            val itemSize = maxWidth
            SubcomposeAsyncImage(
                model = album.thumbnailUrl?.thumbnail(itemSize.px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large),
                loading = {
                    ShimmerHost(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                },
                error = {
                    LaunchedEffect(Unit) {
                        Log.d("LocalAlbumItem", "[DEBUG-ALBUM] Image load failed for Local Album: ${album.title} url: ${album.thumbnailUrl}")
                    }
                    ShimmerHost(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            )
        }
    }
}