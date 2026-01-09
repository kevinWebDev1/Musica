package com.github.musicyou.ui.items

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.ui.components.MusicBars
import com.github.musicyou.ui.components.TextPlaceholder
import com.github.musicyou.ui.styling.NeumorphicColors
import com.github.musicyou.ui.styling.neumorphicPressed
import com.github.musicyou.ui.styling.neumorphicRaised
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.ui.styling.shimmer

@Composable
fun ItemContainer(
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    isPlaying: Boolean = false,
    fullCard: Boolean = false,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    textAlign: TextAlign = TextAlign.Start,
    shape: Shape = MaterialTheme.shapes.large,
    cornerRadius: Dp = 12.dp,
    containerColor: Color? = null,
    contentColor: Color? = null,
    neumorphicColors: NeumorphicColors? = null,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    thumbnail: @Composable () -> Unit
) {
    val currentNeumorphicColors = neumorphicColors ?: rememberNeumorphicColors()

    Column(
        modifier = modifier
            .widthIn(max = 200.dp)
            .padding(12.dp) // Space for shadows
            .then(
                if (fullCard) {
                    if (isPlaying) {
                        Modifier.neumorphicPressed(cornerRadius = cornerRadius, colors = currentNeumorphicColors)
                    } else {
                        Modifier.neumorphicRaised(
                            shadowOffset = 3.dp,
                            shadowRadius = 5.dp,
                            cornerRadius = cornerRadius,
                            colors = currentNeumorphicColors
                        )
                    }
                } else Modifier
            )
            .then(if (fullCard) Modifier.clip(shape) else Modifier)
            .then(
                if (fullCard) Modifier.background(containerColor ?: currentNeumorphicColors.background) else Modifier
            )
            .clickable(
                enabled = onClick != null,
                onClick = onClick ?: {}
            )
            .then(
                if (fullCard) Modifier.padding(12.dp) else Modifier // Internal padding for content
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio = 1F)
                .then(
                    if (!fullCard) {
                        if (isPlaying) {
                            Modifier.neumorphicPressed(cornerRadius = cornerRadius, colors = currentNeumorphicColors)
                        } else {
                            Modifier.neumorphicRaised(
                                shadowOffset = 3.dp,
                                shadowRadius = 5.dp,
                                cornerRadius = cornerRadius,
                                colors = currentNeumorphicColors
                            )
                        }
                    } else Modifier
                )
                .clip(shape)
                .background(if (isPlaceholder) color else if (!fullCard) currentNeumorphicColors.background else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.padding(if (fullCard) 0.dp else 6.dp).clip(shape)) {
                thumbnail()
            }
            
            if (isPlaying && !fullCard) {
                 Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    MusicBars(
                        modifier = Modifier
                            .height(16.dp)
                            .align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isPlaceholder) {
            TextPlaceholder()
        } else {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor ?: currentNeumorphicColors.onBackground
            )
        }

        if (subtitle != null || isPlaceholder) {
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (isPlaceholder) {
            TextPlaceholder()
        } else {
            subtitle?.let {
                Text(
                    text = subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = (contentColor ?: currentNeumorphicColors.onBackground).copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ItemPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large
) {
    ItemContainer(
        modifier = modifier,
        isPlaceholder = true,
        title = "",
        shape = shape,
        color = MaterialTheme.colorScheme.shimmer,
        thumbnail = {}
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListItemContainer(
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    isPlaying: Boolean = false,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    maxLines: Int = 1,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    containerColor: Color = ListItemDefaults.colors().containerColor,
    thumbnail: @Composable (size: Dp) -> Unit,
    thumbnailHeight: Dp = 56.dp,
    thumbnailAspectRatio: Float = 1F,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val neumorphicColors = rememberNeumorphicColors()

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp) // Increase horizontal room for shadows
            .then(
                if (isPlaying) {
                     Modifier.neumorphicPressed(
                        cornerRadius = 12.dp
                    )
                } else {
                    Modifier.neumorphicRaised(
                        shadowOffset = 3.dp,
                        shadowRadius = 5.dp,
                        cornerRadius = 12.dp
                    )
                }
            )
            .clip(MaterialTheme.shapes.large)
            // No background here, ListItem handles it or we override
    ) {
        ListItem(
            headlineContent = {
                if (isPlaceholder) {
                    TextPlaceholder()
                } else {
                    Text(
                        text = title,
                        lineHeight = 16.sp,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                        color = neumorphicColors.onBackground // Use neumorphic text color
                    )
                }
            },
            modifier = Modifier
                .combinedClickable(
                    enabled = onClick != null || onLongClick != null,
                    onClick = onClick ?: {},
                    onLongClick = onLongClick ?: {}
                ),
            supportingContent = {
                if (isPlaceholder) {
                    TextPlaceholder()
                } else {
                    subtitle?.let {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = neumorphicColors.onBackground.copy(alpha = 0.7f) // Subtitle color
                        )
                    }
                }
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .height(height = thumbnailHeight)
                        .aspectRatio(ratio = thumbnailAspectRatio)
                        .clip(MaterialTheme.shapes.medium)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) {
                    thumbnail(thumbnailHeight)
                    
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            MusicBars(
                                modifier = Modifier.height(12.dp),
                                color = Color.White
                            )
                        }
                    }
                }
            },
            trailingContent = {
                if (trailingContent != null) trailingContent()
                else if (onLongClick != null) {
                    IconButton(onClick = onLongClick) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = null,
                            tint = neumorphicColors.onBackground
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = neumorphicColors.background // Use neumorphic background
            )
        )
    }
}

@Composable
fun ListItemPlaceholder(
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 56.dp,
    thumbnailAspectRatio: Float = 1F
) {
    ListItemContainer(
        modifier = modifier,
        isPlaceholder = true,
        title = "",
        color = MaterialTheme.colorScheme.shimmer,
        thumbnail = {},
        thumbnailHeight = thumbnailHeight,
        thumbnailAspectRatio = thumbnailAspectRatio
    )
}