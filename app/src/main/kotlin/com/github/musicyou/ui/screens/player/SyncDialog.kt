package com.github.musicyou.ui.screens.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.R
import com.github.musicyou.sync.session.SessionManager
import com.github.musicyou.sync.session.SessionState
import com.github.musicyou.utils.DisposableListener



enum class SyncMode {
    LOCAL,      // Nearby Connections (same room)
    INTERNET    // WebRTC (long distance)
}

@Composable
fun SyncDialog(
    sessionManager: SessionManager,
    onDismiss: () -> Unit,
    onStartSession: (isLongDistance: Boolean) -> Unit = { sessionManager.startSession() },
    onJoinSession: (code: String, isLongDistance: Boolean) -> Unit = { code, _ -> sessionManager.joinSession(code) }
) {
    val sessionState by sessionManager.sessionState.collectAsState()
    var inputSessionCode by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var syncMode by remember { mutableStateOf(SyncMode.INTERNET) }
    
    // Get current media item from the actual player (same approach as Player.kt)
    val binder = LocalPlayerServiceBinder.current
    var currentMediaItem by remember { mutableStateOf(binder?.player?.currentMediaItem) }
    
    // Listen to player media item and metadata changes
    binder?.player?.DisposableListener {
        object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItem = mediaItem
                android.util.Log.d("MusicSync", "SyncDialog: Media changed to ${mediaItem?.mediaMetadata?.title}")
            }
            
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                // Metadata becomes available after track is loaded - update the item
                currentMediaItem = binder.player.currentMediaItem
                android.util.Log.d("MusicSync", "SyncDialog: Metadata updated - title=${mediaMetadata.title}, artist=${mediaMetadata.artist}")
            }
        }
    }
    
    // Extract metadata from the actual player's current media item
    val displayTitle = currentMediaItem?.mediaMetadata?.title?.toString()
    val displayArtist = currentMediaItem?.mediaMetadata?.artist?.toString()
    val displayThumbnail = currentMediaItem?.mediaMetadata?.artworkUri?.toString()


    // Permissions Logic
    val context = androidx.compose.ui.platform.LocalContext.current
    // State to hold the pending action user wanted to perform (Host/Join)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        // Check if all required permissions are granted
        val allGranted = permissionsResult.all { it.value }
        if (allGranted) {
           pendingAction?.invoke()
           pendingAction = null
        } else {
           // Maybe show a snackbar or alert? For now just reset.
           pendingAction = null
        }
    }

    val checkPermissionsAndExecute = { action: () -> Unit ->
        // For Long Distance mode (WebRTC), we don't need location/bluetooth permissions
        if (syncMode == SyncMode.INTERNET) {
            action()
        } else {
            val permissions = mutableListOf<String>()
            
            // Location is always required for Nearby (prior to S, or associated with scanning)
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
                // Also check if Location Services are enabled (GPS)
                val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                
                if (!isGpsEnabled && !isNetworkEnabled) {
                    // Show toast or alert to enable GPS
                    android.widget.Toast.makeText(context, "Please enable Location/GPS for Nearby Sync", android.widget.Toast.LENGTH_LONG).show()
                    // Optionally open settings:
                    // context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    action()
                }
            } else {
                pendingAction = action
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.sync_session)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (sessionState.sessionId != null || sessionState.isHandshaking) {
                    // Active Session OR Handshaking (Starting/Joining)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (sessionState.sessionId != null) 
                                stringResource(R.string.session_id_label, sessionState.sessionId!!)
                            else 
                                "Session ID: Generating...",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Session Code", sessionState.sessionId)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy session code",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.connected_peers, sessionState.connectedPeers.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    // Sync Status Indicator
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Status icon
                        Text(
                            text = when (sessionState.syncStatus) {
                                SessionState.SyncStatus.WAITING -> "🔴"
                                SessionState.SyncStatus.SYNCING -> "🟡"
                                SessionState.SyncStatus.READY -> "🟢"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Status message
                        Text(
                            text = sessionState.clockSyncMessage ?: when (sessionState.syncStatus) {
                                SessionState.SyncStatus.WAITING -> "Waiting..."
                                SessionState.SyncStatus.SYNCING -> "Syncing clocks..."
                                SessionState.SyncStatus.READY -> "Ready to sync!"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (sessionState.syncStatus) {
                                SessionState.SyncStatus.WAITING -> MaterialTheme.colorScheme.error
                                SessionState.SyncStatus.SYNCING -> MaterialTheme.colorScheme.tertiary
                                SessionState.SyncStatus.READY -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Now Playing Card with metadata
                    if (sessionState.currentMediaId != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (displayThumbnail != null) {
                                    AsyncImage(
                                        model = displayThumbnail,
                                        contentDescription = "Album Art",
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                } else {
                                    // Placeholder icon if no thumbnail
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🎵", style = MaterialTheme.typography.headlineMedium)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                
                                // Title and Artist - from local database or Innertube
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayTitle ?: sessionState.currentMediaId ?: "Unknown",
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (displayArtist != null) {
                                        Text(
                                            text = displayArtist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                
                                // Status indicator
                                Text(
                                    text = if (sessionState.playbackStatus == SessionState.Status.PLAYING) "▶️" else "⏸️",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No track playing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (sessionState.isHost) stringResource(R.string.hosting) else stringResource(R.string.participant),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    // Host-Only Mode Toggle (Host) or Status (Participant)
                    if (sessionState.isHost) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                             Text(
                                 text = "Host-Only Mode (Restrict Guests)",
                                 modifier = Modifier.weight(1f),
                                 style = MaterialTheme.typography.bodyMedium
                             )
                             Switch(
                                 checked = sessionState.hostOnlyMode,
                                 onCheckedChange = { sessionManager.setHostOnlyMode(it) }
                             )
                        }
                    } else if (sessionState.hostOnlyMode) {
                        Row(
                             verticalAlignment = Alignment.CenterVertically,
                             modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                             Icon(
                                 imageVector = Icons.Default.Lock,
                                 contentDescription = "Locked",
                                 tint = MaterialTheme.colorScheme.error,
                                 modifier = Modifier.size(20.dp)
                             )
                             Spacer(modifier = Modifier.width(8.dp))
                             Text(
                                 text = "Controls locked by Host",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.error
                             )
                        }
                    }

                    // Display list of connected participants
                    if (sessionState.connectedPeerNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connected Participants (${sessionState.connectedPeerNames.size}):",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Column(
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                        ) {
                            sessionState.connectedPeerNames.values.forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("👤", modifier = Modifier.padding(end = 8.dp))
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // No Session - Show mode selector
                    Text(
                        text = "Sync Mode",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = syncMode == SyncMode.LOCAL,
                            onClick = { syncMode = SyncMode.LOCAL },
                            label = { Text("📍 Local") }
                        )
                        FilterChip(
                            selected = syncMode == SyncMode.INTERNET,
                            onClick = { syncMode = SyncMode.INTERNET },
                            label = { Text("🌐 Internet") }
                        )
                    }
                    
                    Text(
                        text = if (syncMode == SyncMode.LOCAL) 
                            "Sync with nearby devices using Bluetooth/WiFi"
                        else 
                            "Sync with anyone over the internet (WebRTC)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    if (isJoining) {
                        OutlinedTextField(
                            value = inputSessionCode,
                            onValueChange = { inputSessionCode = it.uppercase() },
                            label = { Text(stringResource(R.string.enter_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (sessionState.sessionId != null) {
                // Disconnect/Stop
                TextButton(
                    onClick = {
                        sessionManager.stopSession()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(if (sessionState.isHost) R.string.stop_session else R.string.disconnect))
                }
            } else {
                if (isJoining) {
                    Button(
                        onClick = {
                            if (inputSessionCode.isNotBlank()) {
                                checkPermissionsAndExecute {
                                    onJoinSession(inputSessionCode, syncMode == SyncMode.INTERNET)
                                    // Don't dismiss - let user see sync status
                                }
                            }
                        },
                        enabled = inputSessionCode.isNotBlank()
                    ) {
                        Text(stringResource(R.string.connect))
                    }
                } else {
                    Row {
                         TextButton(onClick = { isJoining = true }) {
                            Text(stringResource(R.string.join_session))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            checkPermissionsAndExecute {
                                onStartSession(syncMode == SyncMode.INTERNET)
                            }
                        }) {
                            Text(stringResource(R.string.host_session))
                        }
                    }
                }
            }
        },
        dismissButton = {
             if (isJoining && sessionState.sessionId == null) {
                 TextButton(onClick = { isJoining = false }) {
                     Text(stringResource(R.string.cancel))
                 }
             } else {
                 TextButton(onClick = onDismiss) {
                     Text(stringResource(R.string.close))
                 }
             }
        }
    )
}
