package com.github.musicyou.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.innertube.Innertube
import com.github.musicyou.R
import com.github.musicyou.models.Artist
import com.github.musicyou.ui.styling.px
import com.github.musicyou.utils.thumbnail

import com.github.musicyou.ui.components.ShimmerHost

@Composable
fun ArtistItem(
    modifier: Modifier = Modifier,
    artist: Innertube.ArtistItem,
    onClick: () -> Unit
) {
    ItemContainer(
        modifier = modifier,
        title = artist.info?.name ?: "",
        subtitle = artist.subscribersCountText?.replace(
            oldValue = "subscribers",
            newValue = stringResource(id = R.string.subscribers).lowercase()
        ),
        textAlign = TextAlign.Center,
        shape = CircleShape,
        cornerRadius = 1000.dp,
        onClick = onClick
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = artist.thumbnail?.url.thumbnail(maxWidth.px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(maxWidth)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
fun LocalArtistItem(
    modifier: Modifier = Modifier,
    artist: Artist,
    onClick: () -> Unit
) {
    ItemContainer(
        modifier = modifier,
        title = artist.name ?: "",
        textAlign = TextAlign.Center,
        shape = CircleShape,
        cornerRadius = 1000.dp,
        onClick = onClick
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            val itemSize = maxWidth
            if (artist.thumbnailUrl == null) {
                ShimmerHost {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            } else if (artist.thumbnailUrl == "") {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(itemSize / 2),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AsyncImage(
                    model = artist.thumbnailUrl.thumbnail(itemSize.px),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
        }
    }
}