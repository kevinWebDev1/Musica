package com.github.musicyou

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.github.innertube.Innertube
import com.github.innertube.requests.playlistPage
import com.github.innertube.requests.song
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.service.PlayerService
import com.github.musicyou.ui.components.BottomNavigation
import com.github.musicyou.ui.navigation.Navigation
import com.github.musicyou.ui.navigation.Routes
import com.github.musicyou.ui.screens.player.PlayerScaffold
import com.github.musicyou.ui.styling.AppTheme
import com.github.musicyou.utils.asMediaItem
import com.github.musicyou.utils.forcePlay
import com.github.musicyou.utils.intent
import com.github.musicyou.utils.hasReviewedKey
import com.github.musicyou.utils.lastReviewRemindTimeKey
import com.github.musicyou.utils.launchCountKey
import com.github.musicyou.utils.preferences
import com.github.musicyou.ui.components.ReviewReminderDialog
import androidx.core.content.edit
import com.github.musicyou.auth.AuthManager
import com.github.musicyou.ui.screens.auth.GoogleSignInScreen
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is PlayerService.Binder) this@MainActivity.binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private var binder by mutableStateOf<PlayerService.Binder?>(null)
    private var data by mutableStateOf<Uri?>(null)
    private lateinit var authManager: AuthManager

    override fun onStart() {
        super.onStart()
        bindService(intent<PlayerService>(), serviceConnection, BIND_AUTO_CREATE)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize AuthManager
        authManager = AuthManager(this)

        val launchedFromNotification = intent?.extras?.getBoolean("expandPlayerBottomSheet") == true
        data = intent?.data ?: intent?.getStringExtra(Intent.EXTRA_TEXT)?.toUri()

        setContent {
            // Check authentication state
            val currentUser by authManager.currentUser.collectAsState()

            // Show login screen if not authenticated, otherwise show main app
            if (currentUser == null) {
                GoogleSignInScreen(
                    authManager = authManager,
                    onSignedIn = {
                        // User signed in successfully - UI will automatically recompose
                        android.util.Log.i("MainActivity", "User signed in: ${authManager.currentUser.value?.displayName}")
                    }
                )
            } else {
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()
            val playerState = rememberStandardBottomSheetState(
                initialValue = SheetValue.Hidden,
                confirmValueChange = { value ->
                    // Check if participant is locked (sync active + not host + host-only mode)
                    val sessionState = binder?.sessionManager?.sessionState?.value
                    val isParticipantLocked = sessionState?.sessionId != null &&
                                               sessionState.isHost == false &&
                                               sessionState.hostOnlyMode == true
                    
                    // Block collapse/hide for locked participants
                    if (isParticipantLocked && (value == SheetValue.Hidden || value == SheetValue.PartiallyExpanded)) {
                        android.util.Log.d("MusicSync", "MainActivity: Blocking player dismiss (Host-Only Mode)")
                        return@rememberStandardBottomSheetState false
                    }
                    
                    if (value == SheetValue.Hidden) {
                        binder?.stopRadio()
                        binder?.player?.clearMediaItems()
                    }

                    return@rememberStandardBottomSheetState true
                },
                skipHiddenState = false
            )

            AppTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(value = LocalPlayerServiceBinder provides binder) {
                        val menuState = LocalMenuState.current

                        Scaffold(
                            bottomBar = {
                                val navBackStackEntry by navController.currentBackStackEntryAsState()
                                val currentDestination = navBackStackEntry?.destination
                                
                                // Robust check: Class name or String contains
                                val route = currentDestination?.route
                                val isOnboarding = route?.contains("Onboarding") == true || 
                                                  route == "com.github.musicyou.ui.navigation.Routes.Onboarding"

                                if (route != null) {
                                    android.util.Log.d("MainActivity", "BottomBar: route=$route, isOnboarding=$isOnboarding")
                                }

                                AnimatedVisibility(
                                    visible = playerState.targetValue != SheetValue.Expanded && !isOnboarding,
                                    enter = slideInVertically(initialOffsetY = { it / 2 }),
                                    exit = slideOutVertically(targetOffsetY = { it })
                                ) {
                                    BottomNavigation(navController = navController)
                                }
                            }
                        ) { paddingValues ->
                            PlayerScaffold(
                                navController = navController,
                                sheetState = playerState,
                                scaffoldPadding = paddingValues
                            ) {
                                Navigation(
                                    navController = navController,
                                    sheetState = playerState
                                )
                            }
                        }

                        if (menuState.isDisplayed) {
                            ModalBottomSheet(
                                onDismissRequest = menuState::hide,
                                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                dragHandle = {
                                    Surface(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape = MaterialTheme.shapes.extraLarge
                                    ) {
                                        Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
                                    }
                                }
                            ) {
                                menuState.content()
                            }
                        }

                        // Review Reminder Logic
                        val context = androidx.compose.ui.platform.LocalContext.current
                        var showReviewDialog by remember { mutableStateOf(false) }
                        
                        LaunchedEffect(Unit) {
                            val prefs = context.preferences
                            
                            // Increment launch count
                            val currentLaunches = prefs.getInt(launchCountKey, 0) + 1
                            prefs.edit { putInt(launchCountKey, currentLaunches) }
                            
                            val hasReviewed = prefs.getBoolean(hasReviewedKey, false)
                            val lastRemindTime = prefs.getLong(lastReviewRemindTimeKey, 0L)
                            val currentTime = System.currentTimeMillis()
                            
                            // Industry best practice: Don't ask for review immediately.
                            // We wait until at least the 3rd launch to ensure the user has used the app.
                            if (!hasReviewed && 
                                currentLaunches >= 3 && 
                                (currentTime - lastRemindTime) >= 24 * 60 * 60 * 1000) {
                                showReviewDialog = true
                            }
                        }

                        if (showReviewDialog) {
                            ReviewReminderDialog(
                                onReview = {
                                    showReviewDialog = false
                                    context.preferences.edit { putBoolean(hasReviewedKey, true) }
                                    try {
                                        val reviewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                                        reviewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(reviewIntent)
                                    } catch (e: Exception) {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(webIntent)
                                    }
                                },
                                onSkip = {
                                    showReviewDialog = false
                                    context.preferences.edit { putLong(lastReviewRemindTimeKey, System.currentTimeMillis()) }
                                }
                            )
                        }
                    }
                }
            }

            DisposableEffect(binder?.player) {
                val player = binder?.player ?: return@DisposableEffect onDispose { }

                if (player.currentMediaItem == null) scope.launch { playerState.hide() }
                else {
                    if (launchedFromNotification) {
                        intent.replaceExtras(Bundle())
                        scope.launch { playerState.expand() }
                    } else scope.launch { playerState.partialExpand() }
                }

                val listener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null)
                            if (mediaItem.mediaMetadata.extras?.getBoolean("isFromPersistentQueue") != true) scope.launch { playerState.expand() }
                            else scope.launch { playerState.partialExpand() }
                    }
                }

                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            LaunchedEffect(data) {
                val uri = data ?: return@LaunchedEffect

                lifecycleScope.launch(Dispatchers.Main) {
                    when (val path = uri.pathSegments.firstOrNull()) {
                        "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                            val browseId = "VL$playlistId"

                            if (playlistId.startsWith("OLAK5uy_")) {
                                Innertube.playlistPage(browseId = browseId)?.getOrNull()
                                    ?.let {
                                        it.songsPage?.items?.firstOrNull()?.album?.endpoint?.browseId?.let { browseId ->
                                            navController.navigate(
                                                route = Routes.Album(id = browseId)
                                            )
                                        }
                                    }
                            } else navController.navigate(
                                route = Routes.Playlist(id = browseId)
                            )
                        }

                        "channel", "c" -> uri.lastPathSegment?.let { channelId ->
                            navController.navigate(
                                route = Routes.Artist(id = channelId)
                            )
                        }

                        else -> when {
                            path == "watch" -> uri.getQueryParameter("v")
                            uri.host == "youtu.be" -> path
                            else -> null
                        }?.let { videoId ->
                            Innertube.song(videoId)?.getOrNull()?.let { song ->
                                val binder = snapshotFlow { binder }.filterNotNull().first()
                                withContext(Dispatchers.Main) {
                                    binder.player.forcePlay(song.asMediaItem)
                                }
                            }
                        }
                    }
                }

                data = null
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        data = intent.data ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }
}

val LocalPlayerServiceBinder = staticCompositionLocalOf<PlayerService.Binder?> { null }
val LocalPlayerPadding = compositionLocalOf { 0.dp }