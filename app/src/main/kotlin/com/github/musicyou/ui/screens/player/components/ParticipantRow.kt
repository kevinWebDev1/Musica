package com.github.musicyou.ui.screens.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.sync.session.SessionState
import com.github.musicyou.sync.presence.PresenceManager
import com.github.musicyou.ui.styling.neonPurple
import com.github.musicyou.ui.styling.purplishPink

@Composable
fun ParticipantRow(
    sessionState: SessionState,
    myAvatar: String?,
    myName: String?
) {
    // 1. Calculate optimized participant list with instant friend lookup
    // Map of PeerID -> Triple(DisplayName, AvatarUrl, IsFriend)
    var participants by remember { mutableStateOf<List<ProcessedParticipant>>(emptyList()) }
    
    // 2. Profile Cache (UID -> Profile Data)
    var profileCache by remember { mutableStateOf<Map<String, com.github.musicyou.auth.PublicProfile>>(emptyMap()) }
    
    // 3. Fetch profiles for any UIDs we encounter
    // NOTE: sessionState.connectedPeers now comes from RTDB via SessionManager
    LaunchedEffect(sessionState.connectedPeers) {
        sessionState.connectedPeers.forEach { uid ->
            if (!profileCache.containsKey(uid)) {
                 launch {
                     val result = ProfileManager.getPublicProfile(uid)
                     result.getOrNull()?.let { profile ->
                         // Update cache with new profile
                         profileCache = profileCache + (uid to profile)
                     }
                 }
            }
        }
    }

    // Process participant list whenever RTDB members change
    // Note: Self is now filtered in PresenceManager.observeSessionMembers
    LaunchedEffect(sessionState.connectedPeers, sessionState.hostUid, profileCache) {
        // connectedPeers = Set<String> of Firebase UIDs from RTDB (excluding self)
        val processed = sessionState.connectedPeers.map { uid ->
            val dbProfile = profileCache[uid]
            
            ProcessedParticipant(
                id = uid,
                name = dbProfile?.displayName ?: "Loading...",
                avatarUrl = dbProfile?.photoUrl,
                username = dbProfile?.username,
                country = dbProfile?.country,  // NEW: Country for flag display
                isSelf = false, 
                uid = uid,
                isHost = uid == sessionState.hostUid // Use reliable host UID check
            ).also {
                android.util.Log.d("ParticipantRow", "Processing participant: name=${it.name}, uid=$uid, hostUid=${sessionState.hostUid}, isHost=${it.isHost}")
            }
        }
        participants = processed
    }
    
    // Dialog for long-press
    var selectedParticipant by remember { mutableStateOf<ProcessedParticipant?>(null) }
    
    if (selectedParticipant != null) {
        val p = selectedParticipant!!
        AlertDialog(
            onDismissRequest = { selectedParticipant = null },
            title = { Text(p.name) },
            text = {
                Column {
                    if (p.username != null) {
                        Text("@${p.username}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("User ID: ${p.uid?.take(12)}...", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedParticipant = null }) {
                    Text("Close")
                }
            }
        )
    }

    Column {
        val totalCount = 1 + participants.size // Self + Peers (Pending slot is visual only, don't count it)
        Text(
            text = "Participants ($totalCount)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // A. SHOW SELF (Always first) - with gradient if host
            item {
                ParticipantItem(
                    name = "You",
                    avatarUrl = myAvatar,
                    isOnline = true,
                    isHost = sessionState.isHost
                )
            }
            
            // B. SHOW CONNECTED PEERS
            items(participants) { participant ->
                ParticipantItem(
                    name = participant.name,
                    avatarUrl = participant.avatarUrl,
                    isOnline = true,
                    isHost = participant.isHost,
                    onClick = { selectedParticipant = participant },
                    onLongClick = { selectedParticipant = participant }
                )
            }
            
            // C. SHOW PENDING SLOT (Always last)
            item {
                PendingParticipantItem()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParticipantItem(
    name: String,
    avatarUrl: String?,
    isOnline: Boolean,
    isHost: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val gradientBrush = Brush.linearGradient(colors = listOf(neonPurple, purplishPink))
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = (if (onClick != {} || onLongClick != {}) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else Modifier).width(72.dp)  // Fixed width to force name truncation
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Gradient ring for host
            if (isHost) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(gradientBrush)
                )
                // Inner background to create ring effect
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
            
            // Avatar or placeholder
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Online Status Dot (positioned outside the avatar box)
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color.Green, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (isHost) {
            Text(
                text = "(Host)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PendingParticipantItem() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pending")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
        Text(
            text = "⏳",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

// Data Classes for internal processing
data class ProcessedParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val username: String? = null,
    val country: String? = null,  // Country code (e.g., "IN", "US") for flag display
    val isSelf: Boolean,
    val uid: String? = null,
    val isHost: Boolean = false
)

