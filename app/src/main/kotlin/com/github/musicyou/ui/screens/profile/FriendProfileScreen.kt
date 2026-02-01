package com.github.musicyou.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.auth.PublicProfile
import com.github.musicyou.sync.presence.PresenceManager
import com.github.musicyou.sync.presence.UserPresence
import com.github.musicyou.ui.styling.*
import com.github.musicyou.R
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.ui.screens.profile.NeumorphicButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendProfileScreen(
    uid: String,
    pop: () -> Unit,
    onJoinSession: (String) -> Unit,
    onPlaylistClick: (ProfileManager.PublicPlaylist) -> Unit
) {
    val context = LocalContext.current
    // ... (rest of val props)

// ... (skipping unchanged parts)


    val scope = rememberCoroutineScope()
    val colors = rememberNeumorphicColors()
    val clipboardManager = LocalClipboardManager.current
    val playerPadding = LocalPlayerPadding.current
    
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var presence by remember { mutableStateOf<UserPresence?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isUnfollowing by remember { mutableStateOf(false) }

    // Fetch Profile
    LaunchedEffect(uid) {
        ProfileManager.getPublicProfile(uid)
            .onSuccess { 
                profile = it 
                isLoading = false
            }
            .onFailure {
                Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
                isLoading = false
            }
    }

    // Observe Presence
    LaunchedEffect(uid) {
        PresenceManager.observeUserPresence(uid).collect {
            presence = it
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = neonPurple)
        }
        return
    }

    var publicPlaylists by remember { mutableStateOf<List<ProfileManager.PublicPlaylist>>(emptyList()) }
    
    // Fetch Public Playlists
    LaunchedEffect(uid) {
        ProfileManager.getPublicPlaylists(uid)
            .onSuccess { publicPlaylists = it }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = pop) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .neumorphicPressed(cornerRadius = 12.dp)
                                .background(colors.background, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = playerPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HEADER ITEM (Spans full width)
            item(span = { GridItemSpan(2) }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar with Status Ring
                    Box(contentAlignment = Alignment.Center) {
                        val ringBrush = when {
                            presence?.status == "hosting" -> Brush.linearGradient(
                                listOf(Color(0xFFD500F9), Color(0xFFFF4081)) // Purple -> Pink
                            )
                            presence?.status == "participating" -> Brush.linearGradient(
                                listOf(Color(0xFF2962FF), Color(0xFF00B0FF)) // Blue -> Light Blue
                            )
                            presence?.status == "idle" && presence?.currentSong != null -> Brush.linearGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF1DE9B6)) // Cyan -> Teal
                            )
                            presence?.online == true -> Brush.linearGradient(
                                listOf(Color(0xFF00E676), Color(0xFF69F0AE)) // Green -> Light Green
                            )
                            else -> null
                        }
                        
                        if (ringBrush != null) {
                             Box(modifier = Modifier
                                 .size(128.dp)
                                 .clip(CircleShape)
                                 .background(ringBrush)
                             )
                        }
                        
                        AsyncImage(
                            model = profile?.photoUrl ?: R.drawable.app_icon,
                            contentDescription = "Profile Picture",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(Color.Gray.copy(alpha = 0.2f)),
                            contentScale = ContentScale.Crop
                        )
                        
                        // Status Badge if hosting/listening
                        if (presence?.currentSong != null || presence?.status == "hosting") {
                             Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = (-8).dp, y = (-8).dp)
                                    .size(32.dp)
                                    .background(colors.background, CircleShape)
                                    .border(2.dp, colors.background, CircleShape)
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                             ) {
                                 val icon = if (presence?.status == "hosting") Icons.Default.Podcasts else Icons.Default.MusicNote
                                 Icon(icon, contentDescription = null, tint = purplishPink, modifier = Modifier.size(18.dp))
                             }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = profile?.displayName ?: "Unknown",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )
                    
                    Text(
                        text = "@${profile?.username}",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground.copy(alpha = 0.6f)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Status Text
                    val statusText = when {
                        presence?.status == "hosting" -> "Hosting a Session"
                        presence?.status == "participating" -> "Participating in Session"
                        presence?.currentSong != null -> "Listening to ${presence?.currentSong?.title}"
                        presence?.online == true -> "Online"
                        else -> "Last seen ${formatLastSeen(presence?.lastSeen ?: 0)}"
                    }
                    
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (presence?.online == true) Color(0xFF4CAF50) else colors.onBackground.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // BIO
                    if (!profile?.bio.isNullOrBlank()) {
                        Text(
                            text = profile!!.bio!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // LOCATION & VIBES
                    if (!profile?.country.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = colors.onBackground.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = profile!!.country!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onBackground.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (!profile?.favoriteGenres.isNullOrEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            profile!!.favoriteGenres.forEach { genre ->
                                 Box(
                                     modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .neumorphicPressed(cornerRadius = 8.dp)
                                        .background(colors.background, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                 ) {
                                     Text(
                                         text = genre,
                                         style = MaterialTheme.typography.labelSmall,
                                         color = colors.onBackground.copy(alpha = 0.7f)
                                     )
                                 }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Unfollow Button
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isUnfollowing = true
                            scope.launch {
                                 ProfileManager.removeFriend(uid).onSuccess {
                                     Toast.makeText(context, "Unfollowed", Toast.LENGTH_SHORT).show()
                                     pop()
                                 }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        if (isUnfollowing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Red, strokeWidth = 2.dp)
                        } else {
                            Text("Unfollow", fontSize = 12.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
            
                    // SESSION CARD (If Active)
                    if (presence?.sessionId != null && presence?.status != "idle") {
                        val sessionId = presence?.sessionId!!
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .neumorphicRaised(cornerRadius = 16.dp)
                                .background(colors.background, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text("Active Session", style = MaterialTheme.typography.labelMedium, color = colors.onBackground.copy(alpha = 0.6f))
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Podcasts, contentDescription = null, tint = purplishPink)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (presence?.status == "hosting") "Hosting Session" else "Joined Session",
                                            color = colors.onBackground,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "ID: $sessionId",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onBackground.copy(alpha = 0.6f)
                                        )
                                    }
                                    
                                    // Gradient Join Button
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(Color(0xFFD500F9), Color(0xFFFF4081))
                                                )
                                            )
                                            .clickable { 
                                                onJoinSession(sessionId)
                                                Toast.makeText(context, "Joining...", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 20.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = "Join",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                    
                    // PUBLIC LIBRARY TITLE
                    Text(
                        text = "Public Playlists",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 1. Liked Songs
            item {
                PlaylistSquareCard(
                    title = "Liked Songs",
                    subtitle = "Favorites",
                    icon = Icons.Default.Favorite,
                    iconColor = Color.Red,
                    onClick = {
                        onPlaylistClick(
                            ProfileManager.PublicPlaylist(
                                id = "favorites",
                                name = "Liked Songs",
                                songCount = 0 // Count unknown until fetched
                            )
                        )
                    }
                )
            }

            // Real Public Playlists
            items(publicPlaylists.size) { index ->
                val playlist = publicPlaylists[index]
                PlaylistSquareCard(
                    title = playlist.name,
                    subtitle = "${playlist.songCount} songs",
                    icon = Icons.Default.QueueMusic,
                    iconColor = neonPurple,
                    onClick = { onPlaylistClick(playlist) }
                )
            }
            
            if (publicPlaylists.isEmpty()) {
                 item(span = { GridItemSpan(2) }) {
                     Text(
                         "No public playlists yet.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = colors.onBackground.copy(alpha = 0.5f),
                         textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                         modifier = Modifier.padding(24.dp)
                     )
                 }
            }
        }
    }
}

@Composable
fun PlaylistSquareCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    val colors = rememberNeumorphicColors()
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .neumorphicRaised(cornerRadius = 16.dp)
            .background(colors.background, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.background, CircleShape)
                    .neumorphicPressed(cornerRadius = 24.dp), // Inset circle
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onBackground.copy(alpha = 0.6f))
            }
        }
    }
}

fun formatLastSeen(timestamp: Long): String {
    if (timestamp == 0L) return "Offline"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}
