package com.github.musicyou.ui.screens.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.sync.session.SessionManager
import com.github.musicyou.sync.session.SessionState
import com.github.musicyou.ui.screens.player.components.ParticipantRow
import com.github.musicyou.utils.displayNameKey
import com.github.musicyou.utils.observePreference
import com.github.musicyou.utils.profileImageUrlKey
import com.github.musicyou.ui.styling.rememberNeumorphicColors

enum class SyncMode {
    LOCAL,      // Nearby Connections (same room)
    INTERNET    // WebRTC (long distance)
}

@Composable
fun SyncSheetContent(
    sessionManager: SessionManager,
    onDismiss: () -> Unit,
    onStartSession: (isLongDistance: Boolean) -> Unit = { sessionManager.startSession() },
    onJoinSession: (code: String, isLongDistance: Boolean) -> Unit = { code, _ -> sessionManager.joinSession(code) }
) {
    val sessionState by sessionManager.sessionState.collectAsState()
    var inputSessionCode by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var syncMode by remember { mutableStateOf(SyncMode.INTERNET) }
    val toastContext = LocalContext.current
    val myAvatar by observePreference(profileImageUrlKey, "")
    val myName by observePreference(displayNameKey, "You")

    val neumorphicColors = rememberNeumorphicColors()

    // Friend Presence Logic
    val friendsPresence by produceState(initialValue = emptyList<com.github.musicyou.auth.FriendPresence>()) {
        com.github.musicyou.auth.ProfileManager.observeFriendsPresence().collect { value = it }
    }
    val hostingFriends = remember(friendsPresence) {
        friendsPresence.filter { it.status == "hosting" && it.sessionId != null }
    }

    // Show toast when participant joins
    val previousPeerCount = remember { mutableStateOf(0) }
    LaunchedEffect(sessionState.connectedPeers.size) {
        if (sessionState.isHost && sessionState.connectedPeers.size > previousPeerCount.value) {
            val newPeers = sessionState.connectedPeers.drop(previousPeerCount.value)
            newPeers.forEach { peerId ->
                val peerName = sessionState.connectedPeerNames[peerId] ?: "Someone"
                android.widget.Toast.makeText(toastContext, "$peerName joined!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        previousPeerCount.value = sessionState.connectedPeers.size
    }

    // Permissions Logic
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        val allGranted = permissionsResult.all { it.value }
        if (allGranted) {
           pendingAction?.invoke()
           pendingAction = null
        } else {
           pendingAction = null
        }
    }

    val checkPermissionsAndExecute = { action: () -> Unit ->
        if (syncMode == SyncMode.INTERNET) {
            action()
        } else {
            val permissions = mutableListOf<String>()
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                 permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }

            val missing = permissions.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (missing.isEmpty()) {
                val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                
                if (!isGpsEnabled && !isNetworkEnabled) {
                    android.widget.Toast.makeText(context, "Please enable Location/GPS for Nearby Sync", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    action()
                }
            } else {
                pendingAction = action
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (sessionState.sessionId != null || sessionState.isHandshaking) {
            // --- ACTIVE SESSION VIEW ---
            Text(
                text = if (sessionState.isHost) "Hosting Session" else "Connected to Session",
                style = MaterialTheme.typography.titleLarge,
                color = neumorphicColors.onBackground
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Session Code Card
            Card(
                colors = CardDefaults.cardColors(containerColor = neumorphicColors.background),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, neumorphicColors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SESSION CODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = neumorphicColors.onBackground.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = sessionState.sessionId ?: "Generating...",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = neumorphicColors.onBackground,
                            letterSpacing = 2.sp
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Session Code", sessionState.sessionId)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Filled.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.primary)
                        }
                        
                        IconButton(
                            onClick = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Join my MusicYou session! musicyou://sync/join?code=${sessionState.sessionId}")
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Session Link")
                                context.startActivity(shareIntent)
                            }
                        ) {
                            Icon(Icons.Filled.Share, "Share", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync Status Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                val (statusIcon, statusColor) = when (sessionState.syncStatus) {
                    SessionState.SyncStatus.WAITING -> Icons.Filled.FiberManualRecord to MaterialTheme.colorScheme.error
                    SessionState.SyncStatus.SYNCING -> Icons.Filled.FiberManualRecord to MaterialTheme.colorScheme.tertiary
                    SessionState.SyncStatus.READY -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
                    SessionState.SyncStatus.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                }
                
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(12.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                // Status message
                Text(
                    text = sessionState.clockSyncMessage ?: when (sessionState.syncStatus) {
                        SessionState.SyncStatus.WAITING -> "Waiting..."
                        SessionState.SyncStatus.SYNCING -> "Syncing clocks..."
                        SessionState.SyncStatus.READY -> "Ready to sync!"
                        SessionState.SyncStatus.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Participants

            Spacer(modifier = Modifier.height(8.dp))
            
            ParticipantRow(
                sessionState = sessionState,
                myAvatar = myAvatar,
                myName = myName
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Host Only Toggle (Host View)
            if (sessionState.isHost) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(neumorphicColors.onBackground.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Host-Only Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = neumorphicColors.onBackground
                    )
                    Switch(
                        checked = sessionState.hostOnlyMode,
                        onCheckedChange = { sessionManager.setHostOnlyMode(it) }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Stop/Disconnect Button
            Button(
                onClick = {
                    sessionManager.stopSession()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(if (sessionState.isHost) "End Session" else "Leave Session")
            }

        } else {
            // --- START LISTENING PARTY VIEW ---
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(neumorphicColors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Active Friends List
            ActiveFriendsList(
                hostingFriends = hostingFriends,
                onJoin = { code ->
                    checkPermissionsAndExecute {
                        onJoinSession(code, syncMode == SyncMode.INTERNET)
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Start a Listening Party",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = neumorphicColors.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Mode Selection Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Local Card
                SyncModeCard(
                    title = "Local Network",
                    subheading = "Sync with nearby devices on same Wi-Fi",
                    icon = Icons.Filled.SwapHoriz,
                    isSelected = syncMode == SyncMode.LOCAL,
                    onClick = { syncMode = SyncMode.LOCAL },
                    modifier = Modifier.weight(1f)
                )
                
                // Internet Card
                SyncModeCard(
                    title = "Internet",
                    subheading = "Sync with anyone, anywhere",
                    icon = Icons.Filled.Public,
                    isSelected = syncMode == SyncMode.INTERNET,
                    onClick = { syncMode = SyncMode.INTERNET },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (isJoining) {
                 OutlinedTextField(
                    value = inputSessionCode,
                    onValueChange = { inputSessionCode = it.uppercase() },
                    label = { Text("Enter Session Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = neumorphicColors.onBackground.copy(alpha = 0.3f)
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                
                Button(
                    onClick = {
                        if (inputSessionCode.isNotBlank()) {
                            checkPermissionsAndExecute {
                                onJoinSession(inputSessionCode, syncMode == SyncMode.INTERNET)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = inputSessionCode.isNotBlank(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Connect")
                }
                
                TextButton(onClick = { isJoining = false }) {
                    Text("Cancel", color = neumorphicColors.onBackground.copy(alpha = 0.6f))
                }

            } else {
                // Buttons
                Button(
                    onClick = {
                        checkPermissionsAndExecute {
                            onStartSession(syncMode == SyncMode.INTERNET)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Create Session")
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { isJoining = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, neumorphicColors.onBackground.copy(alpha = 0.3f))
                ) {
                    Text("Join Session", color = neumorphicColors.onBackground)
                }
            }
        }
    }
}

@Composable
fun SyncModeCard(
    title: String,
    subheading: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val neumorphicColors = rememberNeumorphicColors()
    
    val containerColor = if (isSelected) 
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) 
    else 
        neumorphicColors.background
        
    val borderStroke = if (isSelected) 
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary) 
    else 
        BorderStroke(1.dp, neumorphicColors.onBackground.copy(alpha = 0.1f))

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = borderStroke
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else neumorphicColors.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (isSelected) MaterialTheme.colorScheme.primary else neumorphicColors.onBackground.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subheading,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = neumorphicColors.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
fun ActiveFriendsList(
    hostingFriends: List<com.github.musicyou.auth.FriendPresence>,
    onJoin: (String) -> Unit
) {
    if (hostingFriends.isEmpty()) return
    
    val neumorphicColors = com.github.musicyou.ui.styling.rememberNeumorphicColors()
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Active Friends",
            style = MaterialTheme.typography.titleSmall,
            color = neumorphicColors.onBackground.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        hostingFriends.forEach { friend ->
            var friendName by remember(friend.uid) { mutableStateOf("Friend") }
            var friendAvatar by remember(friend.uid) { mutableStateOf<String?>(null) }
            
            LaunchedEffect(friend.uid) {
                com.github.musicyou.auth.ProfileManager.getPublicProfile(friend.uid).onSuccess { 
                    friendName = it.displayName 
                    friendAvatar = it.photoUrl
                }
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(neumorphicColors.background, RoundedCornerShape(12.dp))
                    .border(1.dp, neumorphicColors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                // Using AsyncImage directly as AvatarImage might not exist
                 coil3.compose.AsyncImage(
                    model = friendAvatar ?: com.github.musicyou.R.drawable.app_icon,
                    contentDescription = friendName,
                    modifier = Modifier.size(40.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = friendName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = neumorphicColors.onBackground
                    )
                    Text(
                        text = "Hosting a session \uD83C\uDFA7", // Headphones emoji
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Button(
                    onClick = { friend.sessionId?.let { onJoin(it) } },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Join", fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
