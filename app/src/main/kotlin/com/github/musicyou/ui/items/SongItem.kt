package com.github.musicyou.ui.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import androidx.compose.ui.platform.LocalContext
import com.github.innertube.Innertube
import com.github.musicyou.models.Song
import com.github.musicyou.ui.styling.px
import com.github.musicyou.utils.thumbnail
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import com.github.musicyou.Database
import com.github.musicyou.LocalPlayerServiceBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun SongItem(
    modifier: Modifier = Modifier,
    song: Innertube.SongItem,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val binder = LocalPlayerServiceBinder.current
    var isDownloaded by remember { mutableStateOf(false) }

    val videoId = song.info?.endpoint?.videoId
    if (videoId != null) {
        LaunchedEffect(videoId) {
            withContext(Dispatchers.IO) {
                val format = Database.format(videoId).firstOrNull()
                val contentLength = format?.contentLength
                if (contentLength != null) {
                    isDownloaded = binder?.cache?.isCached(videoId, 0, contentLength) == true
                }
            }
        }
    }

    ListItemContainer(
        isDownloaded = isDownloaded,
        modifier = modifier,
        isPlaying = isPlaying,
        title = song.info?.name ?: "",
        subtitle = song.authors?.joinToString(separator = "") { it.name ?: "" },
        onClick = onClick,
        onLongClick = onLongClick,
        thumbnail = { size ->
            AsyncImage(
                model = song.thumbnail?.size(size.px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
            )
        },
        trailingContent = trailingContent
    )
}

@Composable
fun MediaThumbnailPlaceholder(
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val gradientBrush = if (isVideo) {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
    val iconColor = if (isVideo) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = iconColor
        )
    }
}

@Composable
fun LocalSongItem(
    modifier: Modifier = Modifier,
    song: Song,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    thumbnailContent: @Composable (() -> Unit)? = null,
    onThumbnailContent: @Composable (BoxScope.() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val binder = LocalPlayerServiceBinder.current
    var isDownloaded by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        withContext(Dispatchers.IO) {
            val format = Database.format(song.id).firstOrNull()
            val contentLength = format?.contentLength
            if (contentLength != null) {
                isDownloaded = binder?.cache?.isCached(song.id, 0, contentLength) == true
            }
        }
    }

    val isVideo = song.artistsText == "Video"

    ListItemContainer(
        isDownloaded = isDownloaded,
        modifier = modifier,
        isPlaying = isPlaying,
        title = song.title,
        subtitle = "${song.artistsText} • ${song.durationText}",
        onClick = onClick,
        onLongClick = onLongClick,
        thumbnail = { size ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailContent == null) {
                    MediaThumbnailPlaceholder(isVideo = isVideo)

                    if (!song.thumbnailUrl.isNullOrBlank()) {
                        val context = LocalContext.current
                        val request = coil3.request.ImageRequest.Builder(context)
                            .data(song.thumbnailUrl?.thumbnail(size.px))
                            .videoFrameMillis(3000)
                            .build()

                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.medium)
                        )
                    }

                    onThumbnailContent?.invoke(this)
                } else {
                    thumbnailContent()
                }
            }
        },
        trailingContent = trailingContent
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSongItem(
    modifier: Modifier = Modifier,
    song: MediaItem,
    isPlaying: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onThumbnailContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val binder = LocalPlayerServiceBinder.current
    var isDownloaded by remember { mutableStateOf(false) }

    LaunchedEffect(song.mediaId) {
        withContext(Dispatchers.IO) {
            val format = Database.format(song.mediaId).firstOrNull()
            val contentLength = format?.contentLength
            if (contentLength != null) {
                isDownloaded = binder?.cache?.isCached(song.mediaId, 0, contentLength) == true
            }
        }
    }

    val isVideo = song.mediaMetadata.extras?.getBoolean("forceVideo") == true

    ListItemContainer(
        isDownloaded = isDownloaded,
        modifier = modifier,
        isPlaying = isPlaying,
        title = song.mediaMetadata.title.toString(),
        subtitle = if (song.mediaMetadata.extras?.getString("durationText") == null) {
            song.mediaMetadata.artist.toString()
        } else {
            "${song.mediaMetadata.artist} • ${song.mediaMetadata.extras?.getString("durationText")}"
        },
        onClick = onClick,
        onLongClick = onLongClick,
        containerColor = BottomSheetDefaults.ContainerColor,
        thumbnail = { size ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                MediaThumbnailPlaceholder(isVideo = isVideo)

                val artworkUri = song.mediaMetadata.artworkUri
                if (artworkUri != null) {
                    AsyncImage(
                        model = artworkUri.thumbnail(size.px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.medium)
                    )
                }

                onThumbnailContent?.invoke()
            }
        },
        trailingContent = trailingContent
    )
}