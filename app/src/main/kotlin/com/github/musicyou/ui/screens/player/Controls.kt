package com.github.musicyou.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.github.musicyou.Database
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.models.Song
import com.github.musicyou.query
import com.github.musicyou.ui.components.SeekBar
import com.github.musicyou.ui.styling.NeumorphicIconButton
import com.github.musicyou.ui.styling.NeumorphicPlayButton
import com.github.musicyou.ui.styling.NeumorphicToggleButton
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.utils.formatAsDuration
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.trackLoopEnabledKey
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun Controls(
    mediaId: String,
    title: String,
    artist: String,
    shouldBePlaying: Boolean,
    position: Long,
    duration: Long,
    onGoToArtist: (() -> Unit)?,
    isLocked: Boolean = false,
    modifier: Modifier = Modifier
) {
    val binder = LocalPlayerServiceBinder.current
    binder?.player ?: return

    // Get pure neumorphic colors
    val neumorphicColors = rememberNeumorphicColors()

    var trackLoopEnabled by rememberPreference(trackLoopEnabledKey, defaultValue = false)
    var scrubbingPosition by remember(mediaId) { mutableStateOf<Long?>(null) }
    var likedAt by rememberSaveable { mutableStateOf<Long?>(null) }

    // Pulsing glow for play button when playing
    val infiniteTransition = rememberInfiniteTransition(label = "controlsGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "playButtonGlow"
    )

    LaunchedEffect(mediaId) {
        Database.likedAt(mediaId).distinctUntilChanged().collect { likedAt = it }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        // Title with neumorphic text color
        Text(
            text = title,
            modifier = Modifier.basicMarquee(),
            style = MaterialTheme.typography.titleMedium,
            color = neumorphicColors.onBackground,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Artist with neumorphic text color
        Text(
            text = artist,
            modifier = Modifier.clickable(
                enabled = onGoToArtist != null,
                onClick = onGoToArtist ?: {}
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = neumorphicColors.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (duration != C.TIME_UNSET) {
            Spacer(modifier = Modifier.weight(0.2f)) // Reduced from 0.5f to avoid pushing buttons down
        }

        // Using enhanced Neumorphic SeekBar (Minimalist Track Look)
        Box(modifier = Modifier.fillMaxWidth()) {
            SeekBar(
                value = scrubbingPosition ?: position,
                minimumValue = 0,
                maximumValue = duration,
                onDragStart = { if (!isLocked) scrubbingPosition = it },
                onDrag = { delta: Long ->
                    if (!isLocked) {
                        scrubbingPosition = if (duration != C.TIME_UNSET) {
                            scrubbingPosition?.plus(delta)?.coerceIn(0, duration)
                        } else {
                            null
                        }
                    }
                },
                onDragEnd = {
                    if (!isLocked) {
                        scrubbingPosition?.let { pos ->
                            binder.syncSeekTo(pos)
                        }
                        scrubbingPosition = null
                    }
                },
                color = if (isLocked) neumorphicColors.onBackground.copy(alpha = 0.3f) else neumorphicColors.onBackground.copy(alpha = 0.8f),
                backgroundColor = neumorphicColors.background,
                barHeight = 10.dp,
                shape = CircleShape
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Time display with neumorphic colors
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formatAsDuration(scrubbingPosition ?: position),
                style = MaterialTheme.typography.labelMedium,
                color = neumorphicColors.onBackground.copy(alpha = if (isLocked) 0.3f else 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (duration != C.TIME_UNSET) {
                Text(
                    text = formatAsDuration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = neumorphicColors.onBackground.copy(alpha = if (isLocked) 0.3f else 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.5f)) // Reduced from 1f

        // Neumorphic control buttons
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val scope = rememberCoroutineScope()

            // Like button
            NeumorphicToggleButton(
                isActive = likedAt != null,
                onClick = {
                    val currentMediaItem = binder.player.currentMediaItem
                    query {
                        if (Database.like(
                                mediaId,
                                if (likedAt == null) System.currentTimeMillis() else null
                            ) == 0
                        ) {
                            currentMediaItem
                                ?.takeIf { it.mediaId == mediaId }
                                ?.let { Database.insert(currentMediaItem, Song::toggleLike) }
                        }
                        // Trigger backup
                        com.github.musicyou.auth.SyncManager.triggerBackupFavorites()
                    }
                },
                icon = if (likedAt == null) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite,
                size = 44.dp,
                iconSize = 24.dp,
                activeTint = androidx.compose.ui.graphics.Color(0xFFE57373),  // Soft red
                inactiveTint = neumorphicColors.onBackground.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Skip Previous
            NeumorphicIconButton(
                onClick = { if (!isLocked) binder.syncSkipPrevious() },
                icon = Icons.Outlined.SkipPrevious,
                size = 48.dp,
                iconSize = 26.dp,
                tint = if (isLocked) neumorphicColors.onBackground.copy(alpha = 0.3f) else neumorphicColors.onBackground
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Play/Pause OR Lock Icon
            if (isLocked) {
                // Lock Icon
                 Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clickable(
                             indication = null, 
                             interactionSource = remember { MutableInteractionSource() }
                        ) { /* Consume clicks */ },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = neumorphicColors.onBackground.copy(alpha = 0.2f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                // Standard Play Button
                NeumorphicPlayButton(
                    isPlaying = shouldBePlaying,
                    isEnded = binder.player.playbackState == Player.STATE_ENDED,
                    onClick = {
                        if (shouldBePlaying) binder.syncPause() else binder.syncPlay()
                    },
                    size = 72.dp,
                    iconSize = 32.dp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Skip Next
            NeumorphicIconButton(
                onClick = { if (!isLocked) binder.syncSkipNext() },
                icon = Icons.Outlined.SkipNext,
                size = 48.dp,
                iconSize = 26.dp,
                tint = if (isLocked) neumorphicColors.onBackground.copy(alpha = 0.3f) else neumorphicColors.onBackground
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Repeat button
            NeumorphicToggleButton(
                isActive = trackLoopEnabled,
                onClick = { if (!isLocked) trackLoopEnabled = !trackLoopEnabled },
                icon = Icons.Outlined.RepeatOne,
                size = 44.dp,
                iconSize = 24.dp,
                activeTint = MaterialTheme.colorScheme.primary,
                inactiveTint = if (isLocked) neumorphicColors.onBackground.copy(alpha = 0.3f) else neumorphicColors.onBackground.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.weight(0.5f)) // Reduced from 1f
    }
}