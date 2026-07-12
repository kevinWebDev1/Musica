package com.github.musicyou.ui.screens.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionSheet(
    title: String,
    player: Player,
    trackType: Int,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tracks by remember { mutableStateOf<List<Pair<Tracks.Group, Int>>>(emptyList()) }
    var selectedTrack by remember { mutableStateOf<Pair<Tracks.Group, Int>?>(null) }
    var isOffSelected by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        val availableTracks = mutableListOf<Pair<Tracks.Group, Int>>()
        for (group in player.currentTracks.groups) {
            if (group.type == trackType) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        availableTracks.add(group to i)
                        if (group.isTrackSelected(i)) {
                            selectedTrack = group to i
                        }
                    }
                }
            }
        }
        tracks = availableTracks
        isOffSelected = selectedTrack == null && trackType == C.TRACK_TYPE_TEXT
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (trackType == C.TRACK_TYPE_TEXT) {
                    item {
                        TrackItem(
                            label = "Off",
                            isSelected = isOffSelected,
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, true)
                                    .build()
                                onDismiss()
                            }
                        )
                    }
                }

                items(tracks) { (group, trackIndex) ->
                    val format = group.mediaTrackGroup.getFormat(trackIndex)
                    val label = format.buildLabel()
                    val isSelected = selectedTrack?.first == group && selectedTrack?.second == trackIndex

                    TrackItem(
                        label = label,
                        isSelected = isSelected,
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                                )
                                .build()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun Format.buildLabel(): String {
    val language = this.language ?: "Unknown"
    val label = this.label
    val bitrate = if (this.bitrate > 0) " (${this.bitrate / 1000} kbps)" else ""
    return if (label != null) "$label - $language$bitrate" else "$language$bitrate"
}
