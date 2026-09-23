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
import androidx.compose.runtime.Composable
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
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.ui.components.ReviewReminderDialog
import androidx.core.content.edit
import com.github.musicyou.auth.AuthManager
import com.github.musicyou.utils.UpdateManager
import com.github.musicyou.utils.UpdateStatus
import com.github.musicyou.utils.VersionConfig
import com.github.musicyou.ui.components.ForceUpdateDialog
import com.github.musicyou.ui.components.OptionalUpdateDialog

import com.github.musicyou.ui.screens.auth.GoogleSignInScreen
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.musicyou.sync.playback.PlaybackState as SyncPlaybackState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext

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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bindService(intent<PlayerService>(), serviceConnection, BIND_AUTO_CREATE)

        // Initialize AuthManager
        authManager = AuthManager(this)

        val launchedFromNotification = intent?.extras?.getBoolean("expandPlayerBottomSheet") == true
        data = intent?.data 
            ?: (if ((intent?.clipData?.itemCount ?: 0) > 0) intent?.clipData?.getItemAt(0)?.uri else null)
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
        android.util.Log.i("[INTENT-MEDIA]", "onCreate intent action=${intent?.action}, data=$data, clipData=${intent?.clipData}")

        setContent {
            // Check for updates using the Webstore API
            var updateInfo by remember { mutableStateOf<com.github.musicyou.updater.UpdateInfo?>(null) }
            
            LaunchedEffect(Unit) {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                val info = com.github.musicyou.updater.UpdateChecker.checkForUpdates(currentVersion)
                updateInfo = info
            }

            if (updateInfo?.isUpdateAvailable == true) {
                com.github.musicyou.updater.UpdateDialog(
                    updateInfo = updateInfo!!,
                    onDismiss = { updateInfo = null }
                )
            }

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
                        // Prevent expansion beyond PartiallyExpanded (MiniPlayer)
                        if (value == SheetValue.Expanded) {
                            return@rememberStandardBottomSheetState false
                        }

                        // Check if participant is locked (sync active + not host + host-only mode)
                        val sessionState = binder?.sessionManager?.sessionState?.value
                        val isParticipantLocked = sessionState?.sessionId != null &&
                                sessionState.isHost == false &&
                                sessionState.hostOnlyMode == true

                        // Block collapse/hide for locked participants
                        if (isParticipantLocked && value == SheetValue.Hidden) {
                            android.util.Log.d("MusicSync", "MainActivity: Blocking player dismiss (Host-Only Mode)")
                            return@rememberStandardBottomSheetState false
                        }

                        return@rememberStandardBottomSheetState true
                    },
                    skipHiddenState = false
                )

                AppTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // --- Retained YouTubePlayerView (created ONCE, survives navigation) ---
                        val lifecycleOwner = LocalLifecycleOwner.current
                        val ytPlayerRef = remember { mutableStateOf<YouTubePlayer?>(null) }

                        val youtubePlayerView = remember(binder?.youtubeEngine) {
                            binder?.youtubeEngine?.let { engine ->
                                YouTubePlayerView(this@MainActivity).apply {
                                    enableAutomaticInitialization = false
                                    val options = IFramePlayerOptions.Builder(this@MainActivity)
                                        .controls(0)   // Hide YouTube's native controls
                                        .rel(0)        // No related videos at end
                                        .ivLoadPolicy(3) // No annotations
                                        .build()
                                    try {
                                        val videoId = engine.playbackState.value.mediaId?.removePrefix("youtube-embed:") ?: "jNQXAC9IVRw"
                                        initialize(object : AbstractYouTubePlayerListener() {
                                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                                android.util.Log.d("YouTubePlayerRetained", "onReady — player bridge established")
                                                ytPlayerRef.value = youTubePlayer
                                            }
                                            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                                                android.util.Log.d("YouTubePlayerRetained", "onStateChange: $state")
                                                
                                                // If the engine's intent is PLAYING, but the player just paused (e.g. due to being detached by Compose), force it back to play!
                                                if (state == PlayerConstants.PlayerState.PAUSED && engine.playbackState.value.isPlaying) {
                                                    android.util.Log.w("YouTubePlayerRetained", "Player paused unexpectedly while engine intent is PLAYING. Forcing play()")
                                                    youTubePlayer.play()
                                                }
                                                
                                                val isPlaying = state == PlayerConstants.PlayerState.PLAYING
                                                val pbState = when (state) {
                                                    PlayerConstants.PlayerState.PLAYING  -> SyncPlaybackState.STATE_READY
                                                    PlayerConstants.PlayerState.PAUSED   -> SyncPlaybackState.STATE_READY
                                                    PlayerConstants.PlayerState.BUFFERING -> SyncPlaybackState.STATE_BUFFERING
                                                            PlayerConstants.PlayerState.ENDED    -> SyncPlaybackState.STATE_ENDED
                                                    else -> SyncPlaybackState.STATE_IDLE
                                                }
                                                engine.reportStateChange(isPlaying, pbState)
                                            }
                                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                                engine.reportPosition((second * 1000).toLong())
                                            }
                                            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                                                engine.reportDuration((duration * 1000).toLong())
                                            }
                                            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                                android.util.Log.e("YouTubePlayerRetained", "Player error: $error")
                                            }
                                            override fun onApiChange(youTubePlayer: YouTubePlayer) {
                                                android.util.Log.d("YouTubePlayerRetained", "onApiChange")
                                            }
                                        }, options)
                                        android.util.Log.d("YouTubePlayerRetained", "initialize called immediately")
                                    } catch (e: Exception) {
                                        android.util.Log.e("YouTubePlayerRetained", "Already initialized or error: ${e.message}")
                                    }
                                }
                            }
                        }

                        // Lifecycle management for the retained view
                        DisposableEffect(youtubePlayerView, lifecycleOwner) {
                            youtubePlayerView?.let { lifecycleOwner.lifecycle.addObserver(it) }
                            onDispose {
                                youtubePlayerView?.let { lifecycleOwner.lifecycle.removeObserver(it) }
                            }
                        }

                        val videoQualityPref by com.github.musicyou.utils.observePreference(
                            com.github.musicyou.utils.videoQualityKey,
                            com.github.musicyou.enums.VideoQuality.AUTO
                        )

                        var lastMediaIdForQuality by remember { mutableStateOf<String?>(null) }
                        val ytPlaybackState by binder?.youtubeEngine?.playbackState?.collectAsState() ?: remember { mutableStateOf(null) }
                        val currentMediaIdForQuality = ytPlaybackState?.mediaId

                        LaunchedEffect(videoQualityPref, currentMediaIdForQuality) {
                            val isQualityChange = lastMediaIdForQuality != null && lastMediaIdForQuality == currentMediaIdForQuality
                            lastMediaIdForQuality = currentMediaIdForQuality
                            
                            if (videoQualityPref == com.github.musicyou.enums.VideoQuality.AUTO) return@LaunchedEffect
                            if (currentMediaIdForQuality.isNullOrBlank()) return@LaunchedEffect
                            
                            // Give the player a moment to load the iframe
                            kotlinx.coroutines.delay(1000)
                            
                            val targetSize = when (videoQualityPref) {
                                com.github.musicyou.enums.VideoQuality.QUALITY_360P -> 640 to 360
                                com.github.musicyou.enums.VideoQuality.QUALITY_720P -> 1280 to 720
                                com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> 1920 to 1080
                                com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> 2560 to 1440
                                com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> 3840 to 2160
                                else -> return@LaunchedEffect
                            }
                            
                            val (w, h) = targetSize
                            youtubePlayerView?.findWebView()?.let { webView ->
                                android.util.Log.d("YouTubeQuality", "Injecting CSS scaling script for $w x $h")
                                val js = """
                                    (function() {
                                        var iframe = document.querySelector('iframe');
                                        if (iframe) {
                                            var targetW = $w;
                                            var targetH = $h;
                                            
                                            var wMultiplier = targetW / window.innerWidth;
                                            var hMultiplier = targetH / window.innerHeight;
                                            var maxMultiplier = Math.max(wMultiplier, hMultiplier, 1.0);
                                            
                                            iframe.style.width = (100 * maxMultiplier) + '%';
                                            iframe.style.height = (100 * maxMultiplier) + '%';
                                            iframe.style.position = 'absolute';
                                            iframe.style.top = '0';
                                            iframe.style.left = '0';
                                            iframe.style.transformOrigin = 'top left';
                                            iframe.style.transform = 'scale(' + (1 / maxMultiplier) + ')';
                                            
                                            console.log('YouTubeQuality: Spoofed iframe CSS bounds multiplier: ' + maxMultiplier);
                                        } else {
                                            console.log('YouTubeQuality: youtube-player iframe not found!');
                                        }
                                    })();
                                """.trimIndent()
                                webView.evaluateJavascript(js) { result ->
                                    android.util.Log.d("YouTubeQuality", "evaluateJavascript callback result: $result, isQualityChange: $isQualityChange")
                                    if (isQualityChange) {
                                        val videoId = currentMediaIdForQuality.removePrefix("youtube-embed:")
                                        val isPlaying = binder?.youtubeEngine?.playbackState?.value?.isPlaying == true
                                        val currentPosSec = (binder?.youtubeEngine?.playbackState?.value?.currentPositionMs ?: 0L) / 1000f
                                        
                                        android.util.Log.d("YouTubeQuality", "Forcing video reload for quality change: $videoId at $currentPosSec")
                                        binder?.youtubeEngine?.reportStateChange(isPlaying, SyncPlaybackState.STATE_BUFFERING)
                                        if (isPlaying) {
                                            ytPlayerRef.value?.loadVideo(videoId, currentPosSec)
                                        } else {
                                            ytPlayerRef.value?.cueVideo(videoId, currentPosSec)
                                        }
                                    }
                                }
                            }
                        }

                        // --- Centralised command state machine ---
                        LaunchedEffect(ytPlayerRef.value, binder?.youtubeEngine) {
                            val ytPlayer = ytPlayerRef.value ?: return@LaunchedEffect
                            val engine = binder?.youtubeEngine ?: return@LaunchedEffect

                            var lastVideoId: String? = null
                            var lastIsPlaying: Boolean? = null
                            var lastSeekRequestId: Int = -1

                            engine.playbackState.collect { state ->
                                val videoId = state.mediaId?.removePrefix("youtube-embed:")
                                if (videoId.isNullOrBlank()) {
                                    android.util.Log.d("YouTubePlayerRetained", "Engine state update has no video ID. Pausing player.")
                                    ytPlayer.pause()
                                    lastVideoId = null
                                    return@collect
                                }

                                if (videoId != lastVideoId) {
                                    lastVideoId = videoId
                                    lastIsPlaying = state.isPlaying
                                    lastSeekRequestId = state.seekRequestId
                                    android.util.Log.d("YouTubePlayerRetained", "LOAD videoId=$videoId, autoPlay=${state.isPlaying}, startMs=${state.currentPositionMs}")
                                    if (state.isPlaying) {
                                        ytPlayer.loadVideo(videoId, state.currentPositionMs / 1000f)
                                    } else {
                                        ytPlayer.cueVideo(videoId, state.currentPositionMs / 1000f)
                                    }
                                } else {
                                    if (state.seekRequestId != lastSeekRequestId) {
                                        lastSeekRequestId = state.seekRequestId
                                        android.util.Log.d("YouTubePlayerRetained", "SEEK videoId=$videoId, toMs=${state.currentPositionMs}")
                                        ytPlayer.seekTo(state.currentPositionMs / 1000f)
                                        
                                        // Force playback if the intent is playing, in case the player stalled during a seek
                                        if (state.isPlaying) {
                                            ytPlayer.play()
                                        }
                                    }
                                    if (state.isPlaying != lastIsPlaying) {
                                        lastIsPlaying = state.isPlaying
                                        android.util.Log.d("YouTubePlayerRetained", "PLAY_PAUSE videoId=$videoId, isPlaying=${state.isPlaying}")
                                        if (state.isPlaying) ytPlayer.play() else ytPlayer.pause()
                                    }
                                }
                            }
                        }

                        // Build the composable that consumers invoke via LocalYouTubePlayer
                        val youtubePlayerComposable: (@Composable (Modifier) -> Unit)? =
                            remember(youtubePlayerView) {
                                youtubePlayerView?.let { view ->
                                    @Composable { modifier: Modifier ->
                                        com.github.musicyou.ui.screens.player.YouTubePlayerSurface(
                                            retainedView = view,
                                            modifier = modifier
                                        )
                                    }
                                }
                            }

                        CompositionLocalProvider(
                            value = LocalPlayerServiceBinder provides binder,
                        ) {
                            CompositionLocalProvider(
                                value = LocalYouTubePlayer provides youtubePlayerComposable,
                            ) {
                                val menuState = LocalMenuState.current

                            Scaffold(
                                bottomBar = {
                                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                                    val currentDestination = navBackStackEntry?.destination

                                    // Robust check: Class name or String contains
                                    val route = currentDestination?.route
                                    val isOnboarding = route?.contains("Onboarding") == true ||
                                            route == "com.github.musicyou.ui.navigation.Routes.Onboarding"
                                    val isOnFullscreenPlayer = route == "com.github.musicyou.ui.navigation.Routes.FullscreenPlayer"

                                    if (route != null) {
                                        android.util.Log.d("MainActivity", "BottomBar: route=$route, isOnboarding=$isOnboarding, isFullscreen=$isOnFullscreenPlayer")
                                    }

                                    AnimatedVisibility(
                                        visible = playerState.targetValue != SheetValue.Expanded && !isOnboarding && !isOnFullscreenPlayer,
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
                                        val reviewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kevinwebstore.vercel.app/musica#reviewSection"))
                                        reviewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(reviewIntent)
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
            }

                DisposableEffect(binder?.player, data) {
                    val player = binder?.player ?: return@DisposableEffect onDispose { }

                    if (player.currentMediaItem == null && data == null) {
                        scope.launch { playerState.hide() }
                    } else if (player.currentMediaItem != null) {
                        if (launchedFromNotification) {
                            intent.replaceExtras(Bundle())
                            scope.launch { playerState.expand() }
                        } else scope.launch { playerState.partialExpand() }
                    }

                    val listener = object : Player.Listener {
                        // Removed visibility logic to prevent ExoPlayer from hiding HybridPlaybackEngine's MiniPlayer
                    }

                    player.addListener(listener)
                    onDispose { player.removeListener(listener) }
                }

                val context = LocalContext.current
                val prefs = context.preferences
                
                LaunchedEffect(binder?.hybridPlaybackEngine, prefs) {
                    val engine = binder?.hybridPlaybackEngine ?: return@LaunchedEffect
                    var lastMediaId: String? = null
                    engine.playbackState.collectLatest { state ->
                        val currentMediaId = state.mediaId ?: state.mediaItem?.mediaId
                        if (currentMediaId != null && currentMediaId != lastMediaId) {
                            lastMediaId = currentMediaId
                            // Always make sure the MiniPlayer is visible (sheet partially expanded)
                            scope.launch { playerState.partialExpand() }
                            
                            // Automatically jump to fullscreen player when video is loaded
                            val autoExpand = prefs.getBoolean(com.github.musicyou.utils.autoExpandYouTubeVideoKey, true)
                            val isVideo = currentMediaId.startsWith("youtube-embed:") ||
                                    state.mediaItem?.mediaMetadata?.extras?.getBoolean("forceVideo") == true ||
                                    currentMediaId.endsWith(".mp4", ignoreCase = true) ||
                                    currentMediaId.endsWith(".mkv", ignoreCase = true) ||
                                    currentMediaId.endsWith(".webm", ignoreCase = true) ||
                                    currentMediaId.endsWith(".mov", ignoreCase = true)

                            if (autoExpand && isVideo) {
                                scope.launch {
                                    // Add a small delay to ensure the UI/NavHost is fully resumed and ready to accept navigation.
                                    kotlinx.coroutines.delay(150)
                                    android.util.Log.d("ytSync", "MainActivity: Attempting to navigate to FullscreenPlayer for video! mediaId=$currentMediaId")
                                    var retryCount = 0
                                    while (retryCount < 5) {
                                        try {
                                            navController.navigate(com.github.musicyou.ui.navigation.Routes.FullscreenPlayer) {
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                            android.util.Log.d("ytSync", "MainActivity: Successfully navigated to FullscreenPlayer!")
                                            break
                                        } catch (e: Exception) {
                                            retryCount++
                                            android.util.Log.e("ytSync", "MainActivity: Failed to navigate to FullscreenPlayer, retrying ($retryCount/5)", e)
                                            kotlinx.coroutines.delay(300)
                                        }
                                    }
                                }
                            }
                        } else if (currentMediaId == null && data == null) {
                            lastMediaId = null
                            scope.launch { 
                                kotlinx.coroutines.delay(150)
                                if (data == null && engine.playbackState.value.mediaId == null && engine.playbackState.value.mediaItem?.mediaId == null) {
                                    playerState.hide() 
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(data) {
                    val uri = data ?: return@LaunchedEffect
                    android.util.Log.i("[INTENT-MEDIA]", "LaunchedEffect(data) processing uri: $uri")

                    // DEEP LINK: Session Join
                    if (uri.scheme == "musicyou" && uri.host == "sync") {
                        val path = uri.pathSegments.firstOrNull()
                        if (path == "join") {
                            uri.getQueryParameter("code")?.let { code ->
                                android.util.Log.i("DeepLink", "Joining session via Deep Link: $code")
                                val currentBinder = snapshotFlow { binder }.filterNotNull().first()
                                currentBinder.sessionManager.joinSession(code)

                                android.widget.Toast.makeText(this@MainActivity, "Joining session...", android.widget.Toast.LENGTH_SHORT).show()
                                scope.launch { playerState.partialExpand() }
                            }
                        }
                        data = null
                        return@LaunchedEffect
                    }

                    // LOCAL MEDIA (File Manager / Gallery) OR DIRECT STREAM (MovieBox)
                    val isLocalScheme = uri.scheme == "content" || uri.scheme == "file"
                    val isYouTubeHost = uri.host?.contains("youtube.com") == true || uri.host == "youtu.be"
                    val isDirectStream = (uri.scheme == "http" || uri.scheme == "https") && !isYouTubeHost

                    if (isLocalScheme || isDirectStream) {
                        if (uri.scheme == "content") {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("MusicaIntent", "Could not take persistable permission for $uri", e)
                            }
                        }

                        val title = if (uri.scheme == "content") {
                            try {
                                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                        if (nameIdx != -1) cursor.getString(nameIdx) else null
                                    } else null
                                }
                            } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "Media File"
                        } else {
                            uri.lastPathSegment?.substringBefore("?") ?: "MovieBox Stream"
                        }

                        val mimeType = if (uri.scheme == "content") contentResolver.getType(uri) else null
                        val isVideo = mimeType?.startsWith("video/") == true ||
                            title.endsWith(".mp4", ignoreCase = true) ||
                            title.endsWith(".mkv", ignoreCase = true) ||
                            title.endsWith(".webm", ignoreCase = true) ||
                            title.endsWith(".mov", ignoreCase = true) ||
                            title.endsWith(".m3u8", ignoreCase = true)

                        val extras = android.os.Bundle().apply {
                            putBoolean("forceVideo", isVideo)
                        }

                        val mediaItem = androidx.media3.common.MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(uri.toString())
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(if (isDirectStream) "MovieBox Stream" else "Local Media")
                                    .setExtras(extras)
                                    .build()
                            )
                            .build()

                        val currentBinder = snapshotFlow { binder }.filterNotNull().first()
                        android.util.Log.i("[INTENT-MEDIA]", "Loading mediaItem: id=${mediaItem.mediaId}, title=$title, isVideo=$isVideo")
                        currentBinder.hybridPlaybackEngine.loadMediaItem(mediaItem, 0L, autoPlay = true)
                        scope.launch {
                            playerState.partialExpand()
                        }
                        if (isVideo) {
                            scope.launch {
                                kotlinx.coroutines.delay(100)
                                var retryCount = 0
                                while (retryCount < 5) {
                                    try {
                                        android.util.Log.i("[INTENT-MEDIA]", "Navigating to Routes.FullscreenPlayer (attempt ${retryCount + 1})")
                                        navController.navigate(Routes.FullscreenPlayer) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                        android.util.Log.i("[INTENT-MEDIA]", "Successfully navigated to FullscreenPlayer!")
                                        break
                                    } catch (e: Exception) {
                                        retryCount++
                                        android.util.Log.e("[INTENT-MEDIA]", "Navigation to FullscreenPlayer failed, retrying ($retryCount/5)", e)
                                        kotlinx.coroutines.delay(300)
                                    }
                                }
                            }
                        }
                        data = null
                        return@LaunchedEffect
                    }

                    when (val path = uri.pathSegments.firstOrNull()) {
                        "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                            val browseId = "VL$playlistId"

                            if (playlistId.startsWith("OLAK5uy_")) {
                                val page = withContext(Dispatchers.IO) {
                                    Innertube.playlistPage(browseId = browseId)?.getOrNull()
                                }
                                page?.songsPage?.items?.firstOrNull()?.album?.endpoint?.browseId?.let { albumId ->
                                    navController.navigate(route = Routes.Album(id = albumId))
                                }
                            } else navController.navigate(route = Routes.Playlist(id = browseId))
                        }

                        "channel", "c" -> uri.lastPathSegment?.let { channelId ->
                            navController.navigate(route = Routes.Artist(id = channelId))
                        }

                        else -> when {
                            path == "watch" -> uri.getQueryParameter("v")
                            uri.host == "youtu.be" -> path
                            else -> null
                        }?.let { videoId ->
                            val song = withContext(Dispatchers.IO) {
                                Innertube.song(videoId)?.getOrNull()
                            }
                            song?.let {
                                val currentBinder = snapshotFlow { binder }.filterNotNull().first()
                                currentBinder.player.forcePlay(it.asMediaItem)
                                scope.launch { playerState.partialExpand() }
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
        setIntent(intent)
        data = intent.data 
            ?: (if ((intent.clipData?.itemCount ?: 0) > 0) intent.clipData?.getItemAt(0)?.uri else null)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
        android.util.Log.i("[INTENT-MEDIA]", "onNewIntent action=${intent.action}, data=$data, clipData=${intent.clipData}")
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

val LocalPlayerServiceBinder = staticCompositionLocalOf<PlayerService.Binder?> { null }
val LocalYouTubePlayer = staticCompositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }
val LocalPlayerPadding = compositionLocalOf { 0.dp }

fun android.view.ViewGroup.findWebView(): android.webkit.WebView? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is android.webkit.WebView) return child
        if (child is android.view.ViewGroup) {
            val wv = child.findWebView()
            if (wv != null) return wv
        }
    }
    return null
}