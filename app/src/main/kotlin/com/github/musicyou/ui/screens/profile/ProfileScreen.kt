package com.github.musicyou.ui.screens.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import android.widget.Toast
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.auth.FriendPresence
import androidx.compose.runtime.collectAsState
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.ContentCopy
import com.github.musicyou.ui.styling.*
import com.github.musicyou.utils.displayNameKey
import com.github.musicyou.utils.preferences
import com.github.musicyou.utils.profileImageUrlKey
import com.github.musicyou.utils.usernameKey
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.github.musicyou.R
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.net.Uri
import android.content.Context
import com.github.musicyou.utils.isProfileUpdatePendingKey
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.github.musicyou.sync.presence.PresenceManager
import com.github.musicyou.sync.presence.SongInfo

enum class FriendStatus(val label: String, val color: Color) {
    OFFLINE("Offline", Color.Gray),
    ONLINE("Online", Color.Green),
    LISTENING("Listening", Color.Cyan),  // idle + playing music
    HOSTING("Hosting", Color.Transparent), // Special gradient handling
    PARTICIPATING("Participating", Color.Blue)
}

data class FriendUiModel(
    val uid: String,
    val name: String,
    val username: String,
    val photoUrl: String? = null,
    val status: FriendStatus = FriendStatus.OFFLINE,
    val isJoinRequestSent: Boolean = false,
    val updatedAt: Long = 0L,
    val statusLabelOverride: String? = null,
    val sessionId: String? = null,
    val currentSong: SongInfo? = null,  // NEW: Currently playing song
    val isOnline: Boolean = false       // NEW: Explicit online status
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    pop: () -> Unit
) {
    val context = LocalContext.current
    val colors = rememberNeumorphicColors()
    val firebaseUser = remember { Firebase.auth.currentUser }
    
    val displayName = com.github.musicyou.utils.observePreference(displayNameKey, "").value
    val username = com.github.musicyou.utils.observePreference(usernameKey, "").value
    val photoUrl = com.github.musicyou.utils.observePreference(profileImageUrlKey, "").value
    val lastUpdated = com.github.musicyou.utils.observePreference(com.github.musicyou.utils.profileImageLastUpdatedKey, 0L).value

    // DIALOG STATES
    var showAddFriendDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showSyncConfirmation by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var friendToRemove by remember { mutableStateOf<FriendUiModel?>(null) } // For Remove Dialog
    
    // DATA STATES
    var friends by remember { mutableStateOf<List<FriendUiModel>>(emptyList()) }
    var incomingRequests by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    
    var isLoadingFriends by remember { mutableStateOf(true) }
    var isSyncingGoogle by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Observe real-time friend presence
    val friendsPresence by ProfileManager.observeFriendsPresence()
        .collectAsState(initial = emptyList())
    
    // Function to refresh all data
    fun refreshData(onComplete: () -> Unit = {}) {
        scope.launch {
            // 1. Fetch Friends (Now returns full profiles)
            ProfileManager.getFriends().onSuccess { rawFriends ->
                friends = rawFriends.map { data ->
                    val uid = data["uid"].toString()
                    // Find presence for this friend
                    val presence = friendsPresence.find { it.uid == uid }
                    
                    FriendUiModel(
                        uid = uid,
                        name = data["displayName"]?.toString() ?: "Unknown",
                        username = data["username"]?.toString() ?: "unknown",
                        photoUrl = data["photoUrl"]?.toString()?.takeIf { it.isNotBlank() },
                        updatedAt = (data["updatedAt"] as? Long) ?: 0L,
                        // Map presence status to FriendStatus (simplified)
                        status = when (presence?.status) {
                            "idle" -> FriendStatus.ONLINE
                            else -> FriendStatus.OFFLINE
                        },
                        sessionId = presence?.sessionId
                    )
                }
            }
            isLoadingFriends = false
            
            // 2. Fetch Incoming Requests
            ProfileManager.getIncomingRequests().onSuccess { 
                incomingRequests = it 
            }
            
            onComplete()
        }
    }
    
    
    // ViewModel for aggregated presence observation (avoids N RTDB flows)
    val viewModel = remember { ProfileViewModel() }
    
    // Collect aggregated friend presence from single StateFlow
    val friendsPresenceMap by viewModel.friendsPresenceMap.collectAsState()
    
    // Observe friends list and update ViewModel
    LaunchedEffect(friends) {
        if (friends.isNotEmpty()) {
            viewModel.observeFriendsPresence(friends.map { it.uid })
        } else {
            viewModel.clearPresenceObservations()
        }
    }
    
    // Clean up when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearPresenceObservations()
        }
    }
    
    // Update friend status with improved UI mapping (with full data: online, song, session)
    LaunchedEffect(friendsPresenceMap) {
        if (friends.isNotEmpty() && friendsPresenceMap.isNotEmpty()) {
            friends = friends.map { friend ->
                val presence = friendsPresenceMap[friend.uid]
                val legacyPresence = friendsPresence.find { it.uid == friend.uid }
                
                // Derive UI status - simplified (no hosting/participating)
                val derivedStatus = when {
                    // Priority 1: Idle + Playing music = LISTENING
                    presence?.status == "idle" && presence.currentSong != null -> 
                        FriendStatus.LISTENING
                    // Priority 2: Online but idle (no song)
                    presence?.online == true -> 
                        FriendStatus.ONLINE
                    // Default: Offline
                    else -> 
                        FriendStatus.OFFLINE
                }
                
                friend.copy(
                    isOnline = presence?.online ?: false,
                    status = derivedStatus,
                    sessionId = presence?.sessionId ?: legacyPresence?.sessionId,
                    currentSong = presence?.currentSong  // NEW: Song info from RTDB
                )
            }
        }
    }
    
    LaunchedEffect(Unit) {
        refreshData()
    }

    // Check for pending Google Sync
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (context.preferences.getBoolean(isProfileUpdatePendingKey, false)) {
                     showSyncConfirmation = true
                     context.preferences.edit().putBoolean(isProfileUpdatePendingKey, false).apply()
                }
                // Also refresh requests on resume
                refreshData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    
    // Observe current user's own presence for "You" card via ViewModel
    val myPresence by viewModel.ownPresence.collectAsState()
    
    // Start observing own presence
    LaunchedEffect(firebaseUser?.uid) {
        firebaseUser?.uid?.let { uid ->
            viewModel.observeOwnPresence(uid)
        }
    }
    
    // "You" header model with live status
    val myProfileUiModel = remember(displayName, username, photoUrl, lastUpdated, myPresence) {
        val finalPhotoUrl = if (photoUrl.isNotBlank() && lastUpdated > 0) "$photoUrl?ts=$lastUpdated" else photoUrl
        FriendUiModel(
            uid = firebaseUser?.uid ?: "me",
            name = displayName.ifBlank { "You" },
            username = username,
            photoUrl = finalPhotoUrl.ifBlank { firebaseUser?.photoUrl?.toString() },
            status = when {
                myPresence?.status == "hosting" -> FriendStatus.HOSTING
                myPresence?.status == "participating" -> FriendStatus.PARTICIPATING
                myPresence?.status == "idle" && myPresence?.currentSong != null -> FriendStatus.LISTENING
                myPresence?.online == true -> FriendStatus.ONLINE
                else -> FriendStatus.OFFLINE
            },
            statusLabelOverride = "[ You ]",
            sessionId = myPresence?.sessionId,
            currentSong = myPresence?.currentSong,
            isOnline = myPresence?.online ?: false
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = pop) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Notification Bell
                    Box(modifier = Modifier
                         .padding(end = 8.dp)
                         .clickable { showNotificationsSheet = true }
                    ) {
                        IconButton(onClick = { showNotificationsSheet = true }) {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                        }
                        if (incomingRequests.isNotEmpty()) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(8.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground
                )
            )
        },
        containerColor = colors.background
    ) { padding ->
        @OptIn(ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isLoadingFriends, // Bind to existing loading state or create specific one if needed
            onRefresh = { 
                isLoadingFriends = true
                refreshData {
                     Toast.makeText(context, "Refreshed", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Profile Picture area
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(128.dp)
                                .neumorphicRaised(cornerRadius = 64.dp)
                                .clip(CircleShape)
                                .background(colors.background)
                        ) {
                            AsyncImage(
                                model = myProfileUiModel.photoUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // Status dot
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.BottomEnd)
                                .offset(x = (-10).dp, y = (-10).dp) // Moved slightly more upward/inward
                                .background(Color.Green, CircleShape)
                                .border(3.dp, colors.background, CircleShape)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.titleMedium,
                        color = purplishPink,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        NeumorphicButton(
                            onClick = { showEditProfileDialog = true },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Edit,
                            text = "Edit Profile"
                        )
                        NeumorphicButton(
                            onClick = { showAddFriendDialog = true },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.PersonAdd,
                            text = "Send Request"
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Friends",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.onBackground
                        )
                        TextButton(onClick = { /* View All */ }) {
                            Text("View All", color = neonPurple)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // "You" Entry
                item {
                    FriendItem(
                        friend = myProfileUiModel.copy(
                            name = displayName,
                            statusLabelOverride = "[ You ]",
                            updatedAt = lastUpdated
                        ), 
                        isSelf = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                // Friends List
                if (friends.isEmpty() && !isLoadingFriends) {
                    item {
                        Text(
                            text = "No friends added yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else {
                    items(friends) { friend ->
                        // REACT-STYLE: Derive status at render time from presenceMap
                        val livePresence = friendsPresenceMap[friend.uid]
                        val liveStatus = when {
                            livePresence?.status == "hosting" -> FriendStatus.HOSTING
                            livePresence?.status == "participating" -> FriendStatus.PARTICIPATING
                            livePresence?.status == "idle" && livePresence.currentSong != null -> FriendStatus.LISTENING
                            livePresence?.online == true -> FriendStatus.ONLINE
                            else -> FriendStatus.OFFLINE
                        }
                        
                        val liveFriend = friend.copy(
                            status = liveStatus,
                            isOnline = livePresence?.online ?: false,
                            currentSong = livePresence?.currentSong,
                            sessionId = livePresence?.sessionId // Ensure session ID is carried over
                        )
                        
                        FriendItem(
                            friend = liveFriend,
                            onActionClick = {
                                // No specific action needed here yet
                            },
                            onLongClick = {
                                if (!friend.isJoinRequestSent) {
                                    friendToRemove = friend
                                }
                            },
                            isSelf = false
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                
                // PRIVACY SETTINGS SECTION
                item {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = "Privacy Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                item {
                    var shareOnlineStatus by remember { 
                        mutableStateOf(context.preferences.getBoolean(com.github.musicyou.utils.shareOnlineStatusKey, true))
                    }
                    PrivacyToggleItem(
                        title = "Share Online Status",
                        description = "Let friends see when you're online",
                        checked = shareOnlineStatus,
                        onCheckedChange = { newValue ->
                            shareOnlineStatus = newValue
                            context.preferences.edit().putBoolean(com.github.musicyou.utils.shareOnlineStatusKey, newValue).apply()
                            if (!newValue) PresenceManager.updateStatus("idle", null)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                item {
                    var shareListeningStatus by remember { 
                        mutableStateOf(context.preferences.getBoolean(com.github.musicyou.utils.shareListeningStatusKey, true))
                    }
                    PrivacyToggleItem(
                        title = "Share Listening Activity",
                        description = "Let friends see what you're playing",
                        checked = shareListeningStatus,
                        onCheckedChange = { newValue ->
                            shareListeningStatus = newValue
                            context.preferences.edit().putBoolean(com.github.musicyou.utils.shareListeningStatusKey, newValue).apply()
                            if (!newValue) PresenceManager.clearCurrentSong()
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                item {
                    var shareSessionInfo by remember { 
                        mutableStateOf(context.preferences.getBoolean(com.github.musicyou.utils.shareSessionInfoKey, true))
                    }
                    PrivacyToggleItem(
                        title = "Share Session Info",
                        description = "Let friends join your sessions",
                        checked = shareSessionInfo,
                        onCheckedChange = { newValue ->
                            shareSessionInfo = newValue
                            context.preferences.edit().putBoolean(com.github.musicyou.utils.shareSessionInfoKey, newValue).apply()
                        }
                    )
                }
            }
        }
    }

    // NOTIFICATIONS SHEET
    if (showNotificationsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNotificationsSheet = false },
            containerColor = colors.background
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp).padding(horizontal = 24.dp)) {
                Text(
                    text = "Musica Notifications", // Instagram style header
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground
                )
                Spacer(modifier = Modifier.height(24.dp))
                
                if (incomingRequests.isEmpty()) {
                    Text("No pending requests.", color = colors.onBackground.copy(alpha = 0.6f))
                } else {
                    incomingRequests.forEach { req ->
                        val reqUid = req["uid"].toString()
                        val reqName = req["displayName"].toString()
                        val reqUsername = req["username"].toString()
                        val reqPhoto = req["photoUrl"]?.toString()
                        
                        NotificationItem(
                            name = reqName,
                            username = reqUsername,
                            photoUrl = reqPhoto,
                            onConfirm = {
                                scope.launch {
                                    ProfileManager.acceptFriendRequest(reqUid).onSuccess {
                                        Toast.makeText(context, "Request Accepted", Toast.LENGTH_SHORT).show()
                                        refreshData()
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    ProfileManager.rejectFriendRequest(reqUid).onSuccess {
                                        Toast.makeText(context, "Request Deleted", Toast.LENGTH_SHORT).show()
                                        refreshData()
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
    // END NOTIFICATIONS SHEET

    if (showAddFriendDialog) {
        AddFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onSendRequest = { friendUsername -> 
                scope.launch {
                    val result = ProfileManager.sendFriendRequest(friendUsername)
                    result.onSuccess {
                         Toast.makeText(context, "Request Sent to $friendUsername", Toast.LENGTH_SHORT).show()
                         showAddFriendDialog = false
                    }.onFailure { e ->
                         Toast.makeText(context, "${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showEditProfileDialog) {
        EditProfileDialog(
            currentDisplayName = displayName,
            currentUsername = username,
            currentPhotoUrl = photoUrl,
            onDismiss = { showEditProfileDialog = false },
            onSave = { newDisplayName, newUsername, newPhotoUrl ->
                scope.launch {
                    ProfileManager.updateUserProfile(
                        context = context,
                        displayName = newDisplayName,
                        newUsername = newUsername,
                        photoUrl = newPhotoUrl
                    ).onSuccess {
                        Toast.makeText(context, "Profile Updated", Toast.LENGTH_SHORT).show()
                        showEditProfileDialog = false
                    }
                }
            }
        )
    }

    // REMOVE FRIEND DIALOG
    if (friendToRemove != null) {
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            title = { Text("Unfollow @${friendToRemove?.username}?") },
            text = { Text("Are you sure you want to remove this friend? They will be removed from your friends list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uid = friendToRemove!!.uid
                        friendToRemove = null // Dismiss first
                        scope.launch {
                            ProfileManager.removeFriend(uid)
                                .onSuccess {
                                    Toast.makeText(context, "Unfollowed", Toast.LENGTH_SHORT).show()
                                    refreshData()
                                }
                                .onFailure {
                                    Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                ) { Text("Unfollow", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { friendToRemove = null }) { Text("Cancel") }
            },
            containerColor = colors.background,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onBackground
        )
    }

    // Sync Confirmation Logic (Unchanged)
    if (showSyncConfirmation) {
        AlertDialog(
            onDismissRequest = { showSyncConfirmation = false },
            title = { Text("Update Profile Picture?") },
            text = { Text("Do you want to sync your latest Google Profile picture to the app?") },
            confirmButton = {
                TextButton(onClick = {
                    showSyncConfirmation = false
                    isSyncingGoogle = true
                    scope.launch {
                        ProfileManager.syncGoogleProfile(context)
                            .onSuccess {
                                isSyncingGoogle = false
                                Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { e ->
                                isSyncingGoogle = false
                                if (e is FirebaseAuthRecentLoginRequiredException) {
                                    Toast.makeText(context, "Please sign in again to sync.", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                    }
                }) { Text("Yes, Sync", color = purplishPink) }
            },
            dismissButton = {
                TextButton(onClick = { showSyncConfirmation = false }) { Text("No") }
            },
            containerColor = colors.background,
            titleContentColor = colors.onBackground,
            textContentColor = colors.onBackground
        )
    }
    
    if (isSyncingGoogle) {
         Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
             CircularProgressIndicator(color = neonPurple)
         }
    }
}

@Composable
fun NotificationItem(name: String, username: String, photoUrl: String?, onConfirm: () -> Unit, onDelete: () -> Unit) {
    val colors = rememberNeumorphicColors()
    var isProcessing by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AsyncImage(
            model = photoUrl ?: R.drawable.app_icon,
            contentDescription = "Requester",
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.2f)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        
        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Text(text = "requested to follow you", style = MaterialTheme.typography.bodySmall, color = colors.onBackground.copy(alpha = 0.6f))
        }
        
        // Buttons: Confirm (Blue) / Delete (Gray)
        Spacer(modifier = Modifier.width(8.dp))
        
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = neonPurple,
                strokeWidth = 3.dp
            )
        } else {
            Button(
                onClick = {
                    isProcessing = true
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = neonPurple,
                    contentColor = colors.onBackground
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
                enabled = !isProcessing
            ) {
                Text("Confirm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onDelete,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha=0.5f)),
            enabled = !isProcessing
        ) {
            Text("Delete", fontSize = 12.sp, color = colors.onBackground)
        }
    }
}

@Composable
fun AddFriendDialog(onDismiss: () -> Unit, onSendRequest: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val colors = rememberNeumorphicColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Friend Request") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Enter username (@username)") },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedLabelColor = neonPurple,
                    cursorColor = neonPurple
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onSendRequest(text) }) {
                Text("Send", color = purplishPink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = colors.background,
        titleContentColor = colors.onBackground
    )
}

@Composable
fun EditProfileDialog(
    currentDisplayName: String,
    currentUsername: String,
    currentPhotoUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit
) {
    var displayName by remember { mutableStateOf(currentDisplayName) }
    var username by remember { mutableStateOf(currentUsername) }
    var photoUrl by remember { mutableStateOf(currentPhotoUrl) }
    
    val colors = rememberNeumorphicColors()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoUrl = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = photoUrl.ifBlank { R.drawable.app_icon },
                        contentDescription = "Profile Picture",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.Gray.copy(alpha = 0.2f)),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { 
                           val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/"))
                           intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                           context.startActivity(intent)
                           context.preferences.edit().putBoolean(isProfileUpdatePendingKey, true).apply()
                           onDismiss()
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .background(purplishPink, CircleShape)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = neonPurple,
                        cursorColor = neonPurple
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextField(
                    value = username,
                    onValueChange = { 
                        if (it.length <= 20) {
                            username = it.lowercase().filter { c -> 
                                c.isLetterOrDigit() || c == '_' || c == '.' 
                            }
                        }
                    },
                    label = { Text("Username (@)") },
                    supportingText = { 
                        val error = ProfileManager.validateUsername(username)
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Unique. Changeable once a month.")
                        }
                    },
                    isError = ProfileManager.validateUsername(username) != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedLabelColor = neonPurple,
                        cursorColor = neonPurple
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(displayName, username, photoUrl) }) {
                Text("Save", color = purplishPink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = colors.background,
        titleContentColor = colors.onBackground
    )
}

@Composable
fun NeumorphicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    val colors = rememberNeumorphicColors()
    Box(
        modifier = modifier
            .height(56.dp)
            .neumorphicRaised(cornerRadius = 16.dp)
            .background(colors.background, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onBackground)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FriendItem(
    friend: FriendUiModel,
    isSelf: Boolean = false,
    onActionClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = rememberNeumorphicColors()
    val hostGradientStart = Color(0xFFD602EE)
    val hostGradientEnd = Color(0xFFFA2C91)
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .neumorphicSurface(cornerRadius = 16.dp)
            .padding(horizontal = 16.dp)
             // Use combinedClickable for long press
            .combinedClickable(
                onClick = {}, // No-op, visual only or navigate to profile later
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar Box with Status Rings
            Box(contentAlignment = Alignment.Center) {
                // Hosting: Gradient Ring
                if (friend.status == FriendStatus.HOSTING) {
                    Canvas(modifier = Modifier.size(60.dp)) {
                        drawCircle(
                            brush = Brush.linearGradient(listOf(hostGradientStart, hostGradientEnd)),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                } 
                // Participating: Blue Ring
                else if (friend.status == FriendStatus.PARTICIPATING) {
                     Canvas(modifier = Modifier.size(60.dp)) {
                        drawCircle(
                            color = Color(0xFF2196F3),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                // Profile photo
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.background)
                        .padding(2.dp)
                ) {
                    val finalPhotoUrl = if (friend.photoUrl?.isNotBlank() == true && friend.updatedAt > 0) {
                        "${friend.photoUrl}?ts=${friend.updatedAt}"
                    } else {
                        friend.photoUrl
                    }

                    AsyncImage(
                        model = finalPhotoUrl ?: R.drawable.app_icon,
                        contentDescription = "Friend Profile",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }

                // Online Dot (only if NOT hosting/participating, as rings replace dot)
                if (friend.isOnline && friend.status != FriendStatus.HOSTING && friend.status != FriendStatus.PARTICIPATING) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .clip(CircleShape)
                            .background(Color.Green)
                            .border(2.dp, colors.background, CircleShape)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Name and Status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name, 
                    style = MaterialTheme.typography.bodyLarge, 
                    fontWeight = FontWeight.Bold, 
                    color = colors.onBackground
                )
                Text(
                    text = "@${friend.username}", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = colors.onBackground.copy(alpha = 0.6f)
                )
                
                // Status Text / Song
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                     if (friend.currentSong != null) {
                        Text(text = "🎵 ", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "${friend.currentSong.title} - ${friend.currentSong.artist}",
                            style = MaterialTheme.typography.labelSmall,
                            color = neonPurple,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        val statusText = friend.statusLabelOverride ?: when(friend.status) {
                            FriendStatus.HOSTING -> "Hosting Session"
                            FriendStatus.PARTICIPATING -> "Participating"
                            FriendStatus.LISTENING -> "Listening"
                            FriendStatus.ONLINE -> "Online"
                             else -> "Offline"
                        }
                        
                        Text(
                            text = statusText, 
                            style = MaterialTheme.typography.labelSmall, 
                            color = if (friend.status == FriendStatus.OFFLINE) Color.Gray else neonPurple
                        )
                    }
                }
            }
            
            // Copy Button (Only for Host/Participant with SessionID)
            if (!isSelf && (friend.status == FriendStatus.HOSTING || friend.status == FriendStatus.PARTICIPATING) && friend.sessionId != null) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Session ID", friend.sessionId)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied ID", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Session ID",
                        tint = neonPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
@Composable
private fun PrivacyToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = rememberNeumorphicColors()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neumorphicSurface(cornerRadius = 16.dp)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onBackground.copy(alpha = 0.6f)
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = neonPurple,
                checkedTrackColor = purplishPink.copy(alpha = 0.5f)
            )
        )
    }
}

