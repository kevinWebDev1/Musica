package com.github.musicyou.ui.screens.player


import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.flow.firstOrNull

import androidx.media3.ui.AspectRatioFrameLayout
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.videoResizeModeKey
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.musicyou.ui.screens.player.components.TrackSelectionSheet
import com.github.innertube.models.NavigationEndpoint
import com.github.musicyou.Database
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.LocalYouTubePlayer
import com.github.musicyou.R
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.ui.components.BaseMediaItemMenu
import com.github.musicyou.ui.components.TooltipIconButton
import com.github.musicyou.ui.styling.neumorphicPressed
import com.github.musicyou.ui.styling.neumorphicRaised
import com.github.musicyou.ui.styling.neumorphicBasin
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.utils.DisposableListener
import com.github.musicyou.utils.isLandscape
import com.github.musicyou.utils.positionAndDurationState
import com.github.musicyou.utils.seamlessPlay
import com.github.musicyou.utils.shouldBePlaying
import com.github.musicyou.ui.screens.player.social.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.media3.common.util.UnstableApi
import com.github.musicyou.enums.VideoQuality
import com.github.musicyou.sync.protocol.*
import com.github.musicyou.sync.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyRowState
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material.icons.filled.Add
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalAnimationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun Player(
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (String) -> Unit,
    onPop: () -> Unit
) {
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current
    binder?.player ?: return

    val hybridPlaybackState by binder.hybridPlaybackEngine.playbackState.collectAsState()

    val nullableMediaItem = hybridPlaybackState.mediaItem ?: binder.player.currentMediaItem
    
    val isYouTube = nullableMediaItem?.mediaId?.startsWith("youtube-embed:") == true
    val flashContext = when {
        isYouTube -> "youtube"
        nullableMediaItem?.mediaId?.let { it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/") } == true -> "local"
        else -> "music"
    }

    val shouldBePlaying = hybridPlaybackState.isPlaying

    val mediaItem = nullableMediaItem ?: return
    val exoPositionAndDuration by binder.player.positionAndDurationState()
    
    val positionAndDuration = if (isYouTube) {
        Pair(hybridPlaybackState.currentPositionMs, hybridPlaybackState.durationMs ?: 0L)
    } else {
        exoPositionAndDuration
    }
    val nextSongTitle =
        if (binder.player.hasNextMediaItem()) binder.player.getMediaItemAt(binder.player.nextMediaItemIndex).mediaMetadata.title.toString()
        else stringResource(id = R.string.open_queue)

    var videoResizeMode by rememberPreference(videoResizeModeKey, AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var artistId: String? by remember(mediaItem) {
        mutableStateOf(
            mediaItem.mediaMetadata.extras?.getStringArrayList("artistIds")?.let { artists ->
                if (artists.size == 1) artists.first()
                else null
            }
        )
    }

    var isShowingLyrics by rememberSaveable { mutableStateOf(false) }
    var fullScreenLyrics by remember { mutableStateOf(false) }
    var isShowingStatsForNerds by rememberSaveable { mutableStateOf(false) }
    var isQueueOpen by rememberSaveable { mutableStateOf(false) }
    var showSubtitlesSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    var showFlashMessageSheet by rememberSaveable { mutableStateOf(false) }
    var showEmojiPickerSheet by rememberSaveable { mutableStateOf(false) }
    var isShowingSleepTimerDialog by rememberSaveable { mutableStateOf(false) }

    var isShowingSyncSheet by rememberSaveable { mutableStateOf(false) }
    var isShowingSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var isShowingQualityDialog by rememberSaveable { mutableStateOf(false) }
    var videoQuality by com.github.musicyou.utils.rememberPreference(com.github.musicyou.utils.videoQualityKey, com.github.musicyou.enums.VideoQuality.AUTO)
    
    // Collect sync session state for Host-Only Mode and Status UI
    val syncSessionState by binder.sessionManager.sessionState.collectAsState()
    val isParticipantLocked = syncSessionState.sessionId != null && 
                               !syncSessionState.isHost && 
                               syncSessionState.hostOnlyMode
    
    // Block back navigation for participants when Host-Only Mode is ON
    BackHandler(enabled = isParticipantLocked) {
        // Do nothing - block the back press
        android.util.Log.d("MusicSync", "BackHandler: Participant blocked from going back (Host-Only Mode)")
    }
    
    val sleepTimerMillisLeft by (binder.sleepTimerMillisLeft
        ?: flowOf(null))
        .collectAsState(initial = null)

    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(false) }
    
    val availableSpeeds = listOf(1f, 1.25f, 1.5f, 2f, 3f, 5f)
    var baselineSpeed by rememberSaveable { mutableStateOf(1f) }
    var activeSpeed by rememberSaveable { mutableStateOf(1f) }
    var isSpeedGestureActive by remember { mutableStateOf(false) }

    LaunchedEffect(syncSessionState.playbackSpeed) {
        if (baselineSpeed != syncSessionState.playbackSpeed) {
            baselineSpeed = syncSessionState.playbackSpeed
            activeSpeed = syncSessionState.playbackSpeed
        }
    }

    LaunchedEffect(areControlsVisible, isFullScreen) {
        if (areControlsVisible && isFullScreen) {
            kotlinx.coroutines.delay(3000)
            areControlsVisible = false
        }
    }

    // Immersive Mode (Hide System Bars)
    val context = LocalContext.current
    val view = LocalView.current
    
    DisposableEffect(isFullScreen) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)

        if (isFullScreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val sharedPreferences = context.getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE)
            val defaultFullscreenOrientation = sharedPreferences.getInt(com.github.musicyou.utils.defaultFullscreenOrientationKey, 0)
            activity.requestedOrientation = when (defaultFullscreenOrientation) {
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }

        onDispose {
            if (isFullScreen) {
                 insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    
    DisposableEffect(Unit) {
         val activity = context.findActivity()
         onDispose {
             activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
             activity?.window?.let { window ->
                 WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
             }
         }
    }

    val queueState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val syncSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(mediaItem) {
        withContext(Dispatchers.IO) {
            if (artistId == null) {
                val artistsInfo = Database.songArtistInfo(mediaItem.mediaId)
                if (artistsInfo.size == 1) artistId = artistsInfo.first().id
            }
        }
    }

    // Get neumorphic colors for the player screen
    val neumorphicColors = rememberNeumorphicColors()

    // --- SOCIAL INTERACTION STATE ---
    val sessionManager = binder?.sessionManager
    
    // DEBUG: Drift tracker (Display integrated into status bar)
    val currentDrift = syncSessionState.drift
    
    var lastReaction by remember { mutableStateOf<ReactionEvent?>(null) }
    var lastFlashMessage by remember { mutableStateOf<FlashMessageEvent?>(null) }
    var lastKineticTouch by remember { mutableStateOf<KineticTouchEvent?>(null) }

    LaunchedEffect(sessionManager) {
        sessionManager?.socialEvents?.collect { event ->
            when (event) {
                is ReactionEvent -> lastReaction = event
                is FlashMessageEvent -> lastFlashMessage = event
                is KineticTouchEvent -> lastKineticTouch = event
                else -> {}
            }
        }
    }

    val thumbnailContent: @Composable (modifier: Modifier) -> Unit = { modifier ->
        Thumbnail(
            isShowingLyrics = isShowingLyrics,
            onShowLyrics = { isShowingLyrics = it },
            fullScreenLyrics = fullScreenLyrics,
            toggleFullScreenLyrics = { fullScreenLyrics = !fullScreenLyrics },
            isShowingStatsForNerds = isShowingStatsForNerds,
            onShowStatsForNerds = { isShowingStatsForNerds = it },
            modifier = modifier
        )
    }

    // --- NEW: Status Line & Host Toggle Composable ---
    // --- NEW: Status Line & Host Toggle Composable ---
    // --- NEW: Status Line (Top) ---
    val syncStatusLine: @Composable () -> Unit = {
        if (syncSessionState.sessionId != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 8.dp)
            ) {
                DynamicSyncStatusPill(
                    syncSessionState = syncSessionState,
                    currentDrift = currentDrift,
                    currentMediaId = mediaItem?.mediaId
                )

            }
        }
    }

    




    val controlsContent: @Composable (modifier: Modifier) -> Unit = { modifier ->
        Column(modifier = modifier) {
             // Inject Status Content above Controls
            syncStatusLine()
            
            Controls(
                mediaId = mediaItem.mediaId,
                title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                artist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                shouldBePlaying = shouldBePlaying,
                position = positionAndDuration.first,
                duration = positionAndDuration.second,
                onGoToArtist = artistId?.let {
                    { onGoToArtist(it) }
                },
                isLocked = isParticipantLocked
            )

            // SOCIAL: Interaction Triggers (Always visible in Sync Session, even if Locked)
            if (sessionManager != null && syncSessionState.sessionId != null) {
                Spacer(modifier = Modifier.height(10.dp))
                ReactionRow(
                    onEmojiSelected = { sessionManager.sendReaction(it) },
                    onShowEmojiPicker = { showEmojiPickerSheet = true }
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlashMessageRow(
                    contextType = flashContext,
                    onMessageSelected = { sessionManager.sendFlashMessage(it) },
                    onOpenHub = { showFlashMessageSheet = true }
                )

                Spacer(modifier = Modifier.height(4.dp))
            }
            
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(neumorphicColors.background)
    ) {
        // SOCIAL: Bottom layer (Ripples)
        KineticTouchOverlay(event = lastKineticTouch)

        // MEDIA INTERACTION AREA (Double tap for ripples)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 400.dp) // Covers thumbnail/video area roughly
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            sessionManager?.sendKineticTouch(x, y)
                        }
                    )
                }
        )



        val isYouTube = remember(mediaItem) {
            mediaItem.mediaId.startsWith("youtube-embed:")
        }
        val isVideo = remember(mediaItem, syncSessionState.localMatchUri, mediaItem.mediaMetadata.extras?.getBoolean("forceVideo"), mediaItem.mediaMetadata.extras?.getBoolean("isLocal"), isYouTube) {
            val path = syncSessionState.localMatchUri ?: mediaItem.mediaId
            val title = mediaItem.mediaMetadata.title?.toString() ?: ""
            val isVid = isYouTube || 
                    path.startsWith("youtube-video:") ||
                    path.endsWith(".mp4", ignoreCase = true) ||
                    path.endsWith(".mkv", ignoreCase = true) ||
                    path.endsWith(".mov", ignoreCase = true) ||
                    path.endsWith(".webm", ignoreCase = true) ||
                    path.endsWith(".m3u8", ignoreCase = true) ||
                    path.endsWith(".mpd", ignoreCase = true) ||
                    path.contains("index_web", ignoreCase = true) ||
                    path.contains("video", ignoreCase = true) ||
                    title.endsWith(".mp4", ignoreCase = true) ||
                    title.endsWith(".mkv", ignoreCase = true) ||
                    title.endsWith(".mov", ignoreCase = true) ||
                    title.endsWith(".webm", ignoreCase = true) ||
                    title.endsWith(".m3u8", ignoreCase = true) ||
                    title.endsWith(".mpd", ignoreCase = true) ||
                    title.contains("index_web", ignoreCase = true) ||
                    mediaItem.mediaMetadata.extras?.getBoolean("forceVideo") == true ||
                    mediaItem.mediaMetadata.extras?.getBoolean("isLocal") == true
            
            android.util.Log.d("YouTubeSearch", "Player.kt computed isVideo=$isVid for mediaId=${mediaItem.mediaId}, path=$path, forceVideo=${mediaItem.mediaMetadata.extras?.getBoolean("forceVideo")}")
            isVid
        }

        val isLocalItem = remember(mediaItem) {
            mediaItem.mediaId.startsWith("content://") || mediaItem.mediaId.startsWith("file://") || mediaItem.mediaId.startsWith("/")
        }
        var isDownloaded by remember(mediaItem.mediaId) { mutableStateOf(false) }
        LaunchedEffect(mediaItem.mediaId) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (!isLocalItem) {
                        val format = com.github.musicyou.Database.format(mediaItem.mediaId).firstOrNull() as? com.github.musicyou.models.Format
                        val contentLength = format?.contentLength
                        if (contentLength != null) {
                            isDownloaded = binder.cache.isCached(mediaItem.mediaId, 0L, contentLength)
                        }
                    }
                } catch(e: Exception) {}
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp) // Reduced from 16.dp for tighter spacing
        ) {
            if (!isFullScreen) {
                // Top Header Component
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount > 20f) onPop()
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Down Arrow (Back)
                    IconButton(
                        onClick = onPop,
                        modifier = Modifier
                            .neumorphicPressed(cornerRadius = 24.dp)
                            .background(neumorphicColors.background, shape = CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Dismiss",
                            tint = neumorphicColors.onBackground,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Audio / Video Pill Toggle
                    if (syncSessionState.localMatchUri == null) {
                        Row(
                            modifier = Modifier
                                .neumorphicPressed(cornerRadius = 24.dp)
                                .background(neumorphicColors.background, shape = RoundedCornerShape(24.dp))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { binder?.toggleForceVideo(false) }
                                    .background(if (!isVideo) neumorphicColors.onBackground.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Audiotrack,
                                    contentDescription = "Audio",
                                    tint = if (!isVideo) neumorphicColors.onBackground else neumorphicColors.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { binder?.toggleForceVideo(true) }
                                    .background(if (isVideo) neumorphicColors.onBackground.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    tint = if (isVideo) neumorphicColors.onBackground else neumorphicColors.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Right Icons (Menu)
                    IconButton(
                        onClick = {
                            menuState.display {
                                BaseMediaItemMenu(
                                    onDismiss = menuState::hide,
                                    mediaItem = mediaItem,
                                    onStartRadio = {
                                        binder.stopRadio()
                                        binder.player.seamlessPlay(mediaItem)
                                        binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
                                    },
                                    onGoToAlbum = onGoToAlbum,
                                    onGoToArtist = onGoToArtist
                                )
                            }
                        },
                        modifier = Modifier
                            .neumorphicPressed(cornerRadius = 24.dp)
                            .background(neumorphicColors.background, shape = CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = "More",
                            tint = neumorphicColors.onBackground
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1F)
            ) {
                if (isLandscape) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = if (isFullScreen) 0.dp else 16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(if (isFullScreen) 1f else 0.66f)
                                .padding(bottom = if (isFullScreen) 0.dp else 16.dp)
                        ) {
                            if (isYouTube) {
                                Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.fillMaxSize()) {
                                    YouTubeGestureSurface(
                                        modifier = Modifier.fillMaxSize(),
                                        onTap = { areControlsVisible = !areControlsVisible },
                                        onSeek = { binder.syncSeekTo(it) },
                                        durationMs = positionAndDuration.second,
                                        currentPositionMs = positionAndDuration.first,
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        }
                                    ) {
                                        val youtubePlayer = LocalYouTubePlayer.current
                                        if (youtubePlayer != null) {
                                            youtubePlayer(Modifier.fillMaxSize())
                                        }
                                    }
                                    
                                    if (!isFullScreen && areControlsVisible && !isLocked) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            PlayerControlButton(
                                                icon = Icons.Rounded.ScreenRotation,
                                                onClick = { 
                                                    val activity = context.findActivity()
                                                    if (activity != null) {
                                                        if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || 
                                                            activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                        } else {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                        }
                                                    }
                                                }
                                            )
                                            
                                            PlayerControlButton(
                                                text = "Lock",
                                                onClick = { isLocked = !isLocked }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Outlined.Fullscreen,
                                                onClick = { isFullScreen = true }
                                            )
                                        }
                                    }
                                }
                            } else if (isVideo) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    android.util.Log.d("YouTubeSearch", "Rendering VideoSurface (Landscape)")
                                    VideoSurface(
                                        player = binder.player,
                                        modifier = Modifier.fillMaxSize(),
                                        onTap = { areControlsVisible = !areControlsVisible },
                                        onGestureActive = { isSpeedGestureActive = it },
                                        onRewindEnd = { binder.syncSeekTo(binder.player.currentPosition) },
                                        onSeek = { binder.syncSeekTo(it) },
                                        onPlayPause = {
                                            if (shouldBePlaying) binder.syncPause()
                                            else binder.syncPlay()
                                        },
                                        onSpeedChange = { 
                                            activeSpeed = it
                                            binder.syncSetPlaybackSpeed(it) 
                                        },
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        },
                                        baselineSpeed = baselineSpeed,
                                        resizeMode = videoResizeMode,
                                        onResizeModeChange = { videoResizeMode = it }
                                    )
                                    if (!isFullScreen) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isYouTube) {
                                                val qualityText = when (videoQuality) {
                                                    VideoQuality.AUTO -> "Auto"
                                                    VideoQuality.QUALITY_360P -> "360p"
                                                    VideoQuality.QUALITY_720P -> "720p"
                                                    VideoQuality.QUALITY_1080P -> "1080p"
                                                    VideoQuality.QUALITY_1440P -> "1440p"
                                                    VideoQuality.QUALITY_2160P -> "2160p"
                                                }
                                                PlayerControlButton(
                                                    text = qualityText,
                                                    subText = "Quality",
                                                    onClick = { isShowingQualityDialog = true }
                                                )
                                            }

                                            PlayerControlButton(
                                                text = "${baselineSpeed}x",
                                                subText = "Speed",
                                                onClick = {
                                                    val currentIndex = availableSpeeds.indexOf(baselineSpeed)
                                                    val nextIndex = (currentIndex + 1) % availableSpeeds.size
                                                    baselineSpeed = availableSpeeds[nextIndex]
                                                    activeSpeed = baselineSpeed
                                                    binder.syncSetPlaybackSpeed(baselineSpeed)
                                                }
                                            )

                                            PlayerControlButton(
                                                text = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else "Fit",
                                                onClick = {
                                                    videoResizeMode = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Rounded.ScreenRotation,
                                                onClick = { 
                                                    val activity = context.findActivity()
                                                    if (activity != null) {
                                                        if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || 
                                                            activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                        } else {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                        }
                                                    }
                                                }
                                            )

                                            PlayerControlButton(
                                                text = "Lock",
                                                onClick = { isLocked = !isLocked }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Outlined.Fullscreen,
                                                onClick = { isFullScreen = true }
                                            )
                                        }
                                    }
                                }
                            } else {
                                thumbnailContent(
                                    Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (!isFullScreen) {
                            controlsContent(
                                Modifier
                                    .padding(vertical = 8.dp)
                                    .fillMaxHeight()
                                    .weight(1f)
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.windowInsetsPadding(if (isFullScreen) WindowInsets(0, 0, 0, 0) else WindowInsets(0, 0, 0, 0))
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(
                                if (isFullScreen) 1f 
                                else if (syncSessionState.sessionId != null) 0.55f // Give optimal weight in sync session for full uncompressed controls
                                else 1.25f
                            )
                        ) {
                            if (isYouTube) {
                                Box {
                                    YouTubeGestureSurface(
                                        modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        onTap = { areControlsVisible = !areControlsVisible },
                                        onSeek = { binder.syncSeekTo(it) },
                                        durationMs = positionAndDuration.second,
                                        currentPositionMs = positionAndDuration.first,
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        }
                                    ) {
                                        val youtubePlayer = LocalYouTubePlayer.current
                                        if (youtubePlayer != null) {
                                            youtubePlayer(Modifier.fillMaxSize())
                                        }
                                    }
                                    
                                    if (!isFullScreen && areControlsVisible && !isLocked) {
                                        // Bottom Right Controls (Rotate, Lock, Fullscreen)
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(bottom = 16.dp, end = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            PlayerControlButton(
                                                icon = Icons.Rounded.ScreenRotation,
                                                onClick = { 
                                                    val activity = context.findActivity()
                                                    if (activity != null) {
                                                        if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || 
                                                            activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                        } else {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                        }
                                                    }
                                                }
                                            )

                                            PlayerControlButton(
                                                text = "Lock",
                                                onClick = { isLocked = !isLocked }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Outlined.Fullscreen,
                                                onClick = { isFullScreen = true }
                                            )
                                        }
                                    }
                                }
                            } else if (isVideo) {
                                Box {
                                    android.util.Log.d("YouTubeSearch", "Rendering VideoSurface (Portrait)")
                                    VideoSurface(
                                        player = binder.player,
                                        modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        onTap = { areControlsVisible = !areControlsVisible },
                                        onGestureActive = { isSpeedGestureActive = it },
                                        onRewindEnd = { binder.syncSeekTo(binder.player.currentPosition) },
                                        onSeek = { binder.syncSeekTo(it) },
                                        onPlayPause = {
                                            if (shouldBePlaying) binder.syncPause()
                                            else binder.syncPlay()
                                        },
                                        onSpeedChange = { 
                                            activeSpeed = it
                                            binder.syncSetPlaybackSpeed(it) 
                                        },
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        },
                                        baselineSpeed = baselineSpeed,
                                        resizeMode = videoResizeMode,
                                        onResizeModeChange = { videoResizeMode = it }
                                    )
                                    if (!isFullScreen) {
                                        // Top Right Controls (Subtitles, Audio)
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 16.dp, end = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Subtitles
                                            PlayerControlButton(
                                                icon = Icons.Outlined.ClosedCaption,
                                                onClick = { showSubtitlesSheet = true }
                                            )
                                            
                                            // Audio Track
                                            PlayerControlButton(
                                                icon = Icons.Outlined.Audiotrack,
                                                onClick = { showAudioSheet = true }
                                            )
                                        }

                                        // Bottom Right Controls (Rotate, Lock, Fullscreen)
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(bottom = 16.dp, end = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isYouTube) {
                                                val qualityText = when (videoQuality) {
                                                    VideoQuality.AUTO -> "Auto"
                                                    VideoQuality.QUALITY_360P -> "360p"
                                                    VideoQuality.QUALITY_720P -> "720p"
                                                    VideoQuality.QUALITY_1080P -> "1080p"
                                                    VideoQuality.QUALITY_1440P -> "1440p"
                                                    VideoQuality.QUALITY_2160P -> "2160p"
                                                }
                                                PlayerControlButton(
                                                    text = qualityText,
                                                    subText = "Quality",
                                                    onClick = { isShowingQualityDialog = true }
                                                )
                                            }

                                            PlayerControlButton(
                                                text = "${baselineSpeed}x",
                                                subText = "Speed",
                                                onClick = {
                                                    val currentIndex = availableSpeeds.indexOf(baselineSpeed)
                                                    val nextIndex = (currentIndex + 1) % availableSpeeds.size
                                                    baselineSpeed = availableSpeeds[nextIndex]
                                                    activeSpeed = baselineSpeed
                                                    binder.syncSetPlaybackSpeed(baselineSpeed)
                                                }
                                            )

                                            PlayerControlButton(
                                                text = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else "Fit",
                                                onClick = {
                                                    videoResizeMode = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Rounded.ScreenRotation,
                                                onClick = { 
                                                    val activity = context.findActivity()
                                                    if (activity != null) {
                                                        if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || 
                                                            activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                        } else {
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                        }
                                                    }
                                                }
                                            )

                                            PlayerControlButton(
                                                text = "Lock",
                                                onClick = { isLocked = !isLocked }
                                            )

                                            PlayerControlButton(
                                                icon = Icons.Outlined.Fullscreen,
                                                onClick = { isFullScreen = true }
                                            )
                                        }
                                    }
                                }
                            } else {
                                thumbnailContent(
                                    Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                                )
                            }
                        }


                        if (!fullScreenLyrics && !isFullScreen) {
                            controlsContent(
                                Modifier
                                    .padding(vertical = 8.dp)
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }

                // Fullscreen Overlay Controls
                val isBuffering = hybridPlaybackState.playbackState == com.github.musicyou.sync.playback.PlaybackState.STATE_BUFFERING ||
                    (!isYouTube && binder.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING)

                if (isFullScreen && (areControlsVisible || isSpeedGestureActive) && !isLocked) {
                    FullscreenControls(
                        areControlsVisible = areControlsVisible || isSpeedGestureActive,
                        isPlaying = shouldBePlaying,
                        isBuffering = isBuffering,
                        isYouTube = isYouTube,
                        onExitFullscreen = { isFullScreen = false },
                        position = positionAndDuration.first,
                        duration = positionAndDuration.second,
                        onShowSubtitles = { showSubtitlesSheet = true },
                        onShowAudio = { showAudioSheet = true },
                        onLockClick = { 
                            isLocked = true 
                            areControlsVisible = false
                        },
                        activeSpeed = activeSpeed,
                        onCycleSpeed = {
                            val currentIndex = availableSpeeds.indexOf(baselineSpeed)
                            val nextIndex = (currentIndex + 1) % availableSpeeds.size
                            baselineSpeed = availableSpeeds[nextIndex]
                            activeSpeed = baselineSpeed
                            binder.syncSetPlaybackSpeed(baselineSpeed)
                        },
                        onShowQuality = { isShowingQualityDialog = true },
                        videoQualityText = when (videoQuality) {
                            com.github.musicyou.enums.VideoQuality.AUTO -> "Auto"
                            com.github.musicyou.enums.VideoQuality.QUALITY_360P -> "360p"
                            com.github.musicyou.enums.VideoQuality.QUALITY_720P -> "720p"
                            com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> "1080p"
                            com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> "1440p"
                            com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> "2160p"
                        },
                        videoResizeMode = videoResizeMode,
                        onToggleZoom = {
                            videoResizeMode = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        syncSessionState = syncSessionState,
                        currentDrift = currentDrift,
                        currentMediaId = mediaItem?.mediaId,
                        isLocalVideo = isLocalItem,
                        onShowFlashMessages = { showFlashMessageSheet = true },
                        onShowEmojiPicker = { showEmojiPickerSheet = true }
                    )
                }
            }


            if (!isFullScreen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BottomSheetDefaults.ExpandedShape)
                        .background(neumorphicColors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isQueueOpen = true }
                            .padding(horizontal = 32.dp, vertical = 12.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { _, dragAmount ->
                                        if (dragAmount < 0) isQueueOpen = true
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            // Playlist / Queue
                            IconButton(
                                onClick = { isQueueOpen = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = null
                                )
                            }

                            // Sleep Timer
                            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                TooltipIconButton(
                                    description = R.string.sleep_timer,
                                    onClick = { isShowingSleepTimerDialog = true },
                                    icon = if (sleepTimerMillisLeft == null) Icons.Outlined.Timer else Icons.Filled.Timer
                                )
                            }

                            // Sync Session (Middle)
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                    .clickable { isShowingSyncSheet = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Group, 
                                    contentDescription = "Sync Session", 
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                                
                                // Subtle Connection/Resyncing Indicator
                                if (syncSessionState.sessionId != null) {
                                    val isResyncing = syncSessionState.clockSyncMessage == "Resyncing..."
                                    val dotColor = if (isResyncing) androidx.compose.ui.graphics.Color(0xFFFFA500) else androidx.compose.ui.graphics.Color.Green
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = (-2).dp, y = 2.dp)
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                    )
                                }
                            }

                            // Download Button (Hide for local items to prevent crowding)
                            if (!isLocalItem) {
                                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    val context = LocalContext.current
                                    IconButton(
                                        onClick = {
                                            if (!isDownloaded) {
                                                binder?.let { playerService ->
                                                    val dataSourceFactory = playerService.createCacheDataSource()
                                                    if (dataSourceFactory is androidx.media3.datasource.cache.CacheDataSource.Factory) {
                                                        com.github.musicyou.utils.DownloadManager.downloadSong(
                                                            context = context,
                                                            mediaItem = mediaItem ?: return@IconButton,
                                                            cacheDataSourceFactory = dataSourceFactory
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Outlined.Download,
                                            contentDescription = "Download"
                                        )
                                    }
                                }
                            }

                            // Persistent Speed Selector
                            IconButton(
                                onClick = { isShowingSpeedDialog = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(imageVector = Icons.Outlined.Speed, contentDescription = "Speed")
                            }
                            
                            val hasLocalMatch = syncSessionState.localMatchUri != null || syncSessionState.localOverride != null
                            if (hasLocalMatch) {
                                val isActive = syncSessionState.clockSyncMessage?.contains("Local") == true || syncSessionState.localOverride != null
                                val sourceColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                val sourceIcon = if (isActive) Icons.Filled.Folder else Icons.Outlined.Folder
                                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    TooltipIconButton(
                                        description = if (isActive) R.string.playing_from_local else R.string.switch_to_local,
                                        onClick = { binder.sessionManager.forcePlayLocal() },
                                        icon = sourceIcon,
                                        tint = sourceColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isShowingSleepTimerDialog) {
            SleepTimer(
                sleepTimerMillisLeft = sleepTimerMillisLeft,
                onDismiss = { isShowingSleepTimerDialog = false }
            )
        }
        
        if (isShowingQualityDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { isShowingQualityDialog = false },
                title = { Text(text = "Video Quality") },
                text = {
                    Column {
                        val qualities = com.github.musicyou.enums.VideoQuality.values().toList()
                        qualities.forEach { quality ->
                            TextButton(
                                onClick = {
                                    videoQuality = quality
                                    val currentPos = binder.player.currentPosition
                                    val currentMediaItem = binder.player.currentMediaItem
                                    if (currentMediaItem != null) {
                                        binder.player.setMediaItem(currentMediaItem)
                                        binder.player.seekTo(currentPos)
                                        binder.player.prepare()
                                        binder.player.play()
                                    }
                                    isShowingQualityDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val text = when (quality) {
                                    com.github.musicyou.enums.VideoQuality.AUTO -> "Auto"
                                    com.github.musicyou.enums.VideoQuality.QUALITY_360P -> "360p"
                                    com.github.musicyou.enums.VideoQuality.QUALITY_720P -> "720p"
                                    com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> "1080p"
                                    com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> "1440p"
                                    com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> "2160p"
                                }
                                Text(
                                    text = text,
                                    fontWeight = if (videoQuality == quality) FontWeight.Bold else FontWeight.Normal,
                                    color = if (videoQuality == quality) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isShowingQualityDialog = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
            )
        }
        
        if (isShowingSpeedDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { isShowingSpeedDialog = false },
                title = { Text(text = "Playback Speed") },
                text = {
                    Column {
                        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                        speeds.forEach { speed ->
                            TextButton(
                                onClick = {
                                    binder.syncSetPlaybackSpeed(speed)
                                    isShowingSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (speed == 1.0f) "Normal" else "${speed}x",
                                    fontWeight = if (binder.player.playbackParameters.speed == speed) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isShowingSpeedDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // --- NEW: Sync Sheet ---
        if (isShowingSyncSheet) {
            ModalBottomSheet(
                onDismissRequest = { isShowingSyncSheet = false },
                sheetState = syncSheetState,
                containerColor = neumorphicColors.background,
                dragHandle = {
                   Surface(
                       modifier = Modifier.padding(vertical = 12.dp),
                       color = neumorphicColors.onBackground.copy(alpha = 0.2f),
                       shape = MaterialTheme.shapes.extraLarge
                   ) {
                       Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
                   }
                }
            ) {
               SyncSheetContent(
                   sessionManager = binder.sessionManager,
                   onDismiss = { isShowingSyncSheet = false },
                   onStartSession = { isLongDistance ->
                       binder.startSyncSession(isLongDistance)
                   },
                   onJoinSession = { code, isLongDistance ->
                       binder.joinSyncSession(code, isLongDistance)
                   }
               )
            }
        }

        if (isQueueOpen) {
            ModalBottomSheet(
                onDismissRequest = { isQueueOpen = false },
                modifier = Modifier.fillMaxWidth(),
                sheetState = queueState,
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
                Queue(
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist
                )
            }
        }
        
        if (showSubtitlesSheet) {
            TrackSelectionSheet(
                title = "Subtitles",
                player = binder.player,
                trackType = androidx.media3.common.C.TRACK_TYPE_TEXT,
                onDismiss = { showSubtitlesSheet = false }
            )
        }

        if (showAudioSheet) {
            TrackSelectionSheet(
                title = "Audio Track",
                player = binder.player,
                trackType = androidx.media3.common.C.TRACK_TYPE_AUDIO,
                onDismiss = { showAudioSheet = false }
            )
        }
        
        if (showFlashMessageSheet) {
            FlashMessageHubSheet(
                contextType = flashContext,
                onSendMessage = { binder.sessionManager.sendFlashMessage(it) },
                onDismiss = { showFlashMessageSheet = false }
            )
        }

        if (showEmojiPickerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showEmojiPickerSheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp, top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Send Emoji Reaction",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    val extendedEmojis = listOf(
                        "❤️", "🔥", "🙌", "😭", "😮", "🕺", "✨", "💯",
                        "😀", "😅", "😂", "🤣", "🥲", "😊", "😇", "🙃",
                        "😍", "🥰", "😘", "😋", "😜", "🤪", "😎", "🤩",
                        "🥳", "😏", "😒", "😔", "😕", "🥺", "😢", "😤",
                        "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨",
                        "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😬",
                        "🙄", "🥱", "😴", "🤤", "😵", "🤐", "🥴", "🤢",
                        "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈",
                        "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "👽",
                        "👾", "🤖", "🎃", "😺", "😸", "😹", "😻", "😼",
                        "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎",
                        "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘",
                        "💝", "❤️‍🔥", "❤️‍🩹", "💢", "💥", "💫", "💦", "💨",
                        "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
                        "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
                        "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛",
                        "🤜", "👏", "👐", "🤲", "🤝", "🙏", "💪", "👀"
                    )
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(48.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(extendedEmojis.size) { index ->
                            val emoji = extendedEmojis[index]
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        binder.sessionManager.sendReaction(emoji)
                                        showEmojiPickerSheet = false
                                    }
                            ) {
                                Text(text = emoji, fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        }

        // SOCIAL: Top layer (Floating Emojis & Cards)
        Box(modifier = Modifier.fillMaxSize().zIndex(100f)) {
            ReactionOverlay(event = lastReaction)
            FlashMessageOverlay(event = lastFlashMessage)
            
            ManualMatchOverlay(
                sessionState = syncSessionState,
                sessionManager = binder.sessionManager
            )
        }
    }
}

@Composable
fun DynamicSyncStatusPill(
    syncSessionState: SessionState,
    currentDrift: Long?,
    currentMediaId: String?,
    isLocalVideo: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isVideo = (currentMediaId != null && (
        currentMediaId.startsWith("youtube-embed:") ||
        currentMediaId.endsWith(".mp4", ignoreCase = true) ||
        currentMediaId.endsWith(".mkv", ignoreCase = true) ||
        currentMediaId.endsWith(".mov", ignoreCase = true) ||
        currentMediaId.endsWith(".webm", ignoreCase = true) ||
        currentMediaId.endsWith(".m3u8", ignoreCase = true) ||
        currentMediaId.endsWith(".mpd", ignoreCase = true) ||
        currentMediaId.contains("index_web", ignoreCase = true) ||
        currentMediaId.contains("video", ignoreCase = true)
    )) || isLocalVideo || syncSessionState.localMatchUri != null

    val count = syncSessionState.connectedPeers.size
    val statusColor = if (count == 0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
    
    val pingDisplay = if (currentDrift != null && currentDrift > 0) "⚡ Δ${currentDrift}ms" else ""
    val pingColor =
        if (currentDrift != null && currentDrift > 1500)
            MaterialTheme.colorScheme.error
        else
            Color(0xFF2E7D32)

    val countText = when (count) {
        0 -> if (isVideo) "no one watching" else "no one listening"
        1 -> if (isVideo) "1 watching" else "1 listening"
        else -> if (isVideo) "$count watching" else "$count listening"
    }

    val mainText = if (syncSessionState.isHost) {
        if (isVideo) "🍿 Watching Video • $countText" else "🎧 Listening to Music • $countText"
    } else {
        if (isVideo) "🍿 Synced with Host • Watching Video" else "🎧 Synced with Host • Listening to Music"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = if (syncSessionState.isHost) Icons.Filled.FiberManualRecord else Icons.Filled.Link,
            contentDescription = "Status",
            tint = statusColor,
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        
        Text(
            text = mainText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = statusColor,
            maxLines = 1
        )

        if (pingDisplay.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = pingDisplay,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = pingColor,
                maxLines = 1
            )
        }
    }
}

val commonPlaybackPresets = listOf(
    // Playback
    "Toggle play/pause ⏯️", "Replay that 🔁", "Skip intro ⏭️",

    // Speed
    "Speed it up ⏩", "Normal speed ▶️",

    // Audio
    "Can't hear you 🎧",

    // Sync
    "Am I behind? 🤔", "Out of sync? 🔄", "Resync pls 🔁",

    // IRL
    "One sec 📱", "Brb 2 min 🚻",

    // Seek
    "Rewind 10s ⏪", "Forward 10s ⏩",
    "More Rewind 10s ⏪", "More Forward 10s ⏩"
)

fun getContextFlashMessages(contextType: String): List<String> {
    return when (contextType) {
        "local" -> listOf(
            "Wait wait wait ✋", "LMAOOO 😂😂", "NO WAY 😱", "Pause, I'm crying 💀",
            "This scene is peak 🍿", "No way, what just happened 🤯", "Wait, who is this again? 🤔",
            "Yoo — plot twist incoming ⏸️", "This is why I love this movie ❤️", "What a shot! 🎬"
        )

        "youtube" -> listOf(
            "This part is gold 🏆",
            "Rate this 1-10 ⭐",
            "LMAOOO 😂😂",
            "Wait, what did they say? 🗣️",
            "Check this out! 🤯",
            "Old is gold",
            "This hits different 💔",
            "Singing along 🎤",
            "Give it a chance 🎧",
            "First time hearing 👂",
            "Good pick 👌"
        )

        else -> listOf(
            "Goosebumps ✨", "Vibing hard 🕺", "This hits different 💔", "Therapy in audio 🧘",
            "Singing along 🎤", "Add to playlist ➕", "See the Lyrics 📝", "First time hearing 👂",
            "Underrated fr 📉", "This is OUR song 🫶", "Your taste tho 🔥",
            "My turn to pick song 🎲", "Missed listening together 🫂",
            "Nice song 🎶", "My favorite song ❤️", "I like this song 👍", "Best lines 🔥"
        )
    }
}

fun getDefaultFlashMessages(contextType: String): List<String> {
    return getContextFlashMessages(contextType) + commonPlaybackPresets
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashMessageHubSheet(
    contextType: String,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    
    val contextDefaultPresets = getContextFlashMessages(contextType)
    
    var savedCustomPresetsStr by com.github.musicyou.utils.rememberPreference("saved_flash_presets_$contextType", "")
    var currentPresets by remember(savedCustomPresetsStr) {
        mutableStateOf(
            if (savedCustomPresetsStr.isBlank()) contextDefaultPresets
            else {
                val loaded = savedCustomPresetsStr.split("|||").filter { it.isNotBlank() }
                loaded + contextDefaultPresets.filter { !loaded.contains(it) }
            }
        )
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ Flash Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                TextButton(onClick = { isEditMode = !isEditMode }) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Filled.Check else Icons.Filled.Edit,
                        contentDescription = "Edit Presets",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = if (isEditMode) "Done" else "Edit Presets")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type any quick message...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (textInput.isNotBlank()) {
                            val trimmed = textInput.trim()
                            val isSaved = currentPresets.contains(trimmed)
                            
                            IconButton(onClick = {
                                if (trimmed.isNotEmpty()) {
                                    val updatedList = if (isSaved) {
                                        currentPresets.filter { it != trimmed }
                                    } else {
                                        listOf(trimmed) + currentPresets
                                    }
                                    currentPresets = updatedList
                                    savedCustomPresetsStr = updatedList.joinToString("|||")
                                }
                            }) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    contentDescription = if (isSaved) "Remove Preset" else "Save Preset",
                                    tint = Color(0xFFFFC107)
                                )
                            }
                            
                            IconButton(onClick = {
                                val trimmed = textInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    onSendMessage(trimmed)
                                    textInput = ""
                                    onDismiss()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send Instantly",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Preset Messages (One-Tap)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val allPresets = currentPresets
            
            val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
            
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                val fromIndex = from.index
                val toIndex = to.index
                // Allow reordering all presets
                val list = currentPresets.toMutableList()
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                currentPresets = list
                savedCustomPresetsStr = list.joinToString("|||")
            }

            androidx.compose.foundation.lazy.LazyRow(
                state = lazyListState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    count = allPresets.size,
                    key = { index -> "preset_${allPresets[index]}" }
                ) { index ->
                    val msg = allPresets[index]
                    val isCustom = true
                    
                    ReorderableItem(
                        state = reorderableState,
                        key = "preset_$msg"
                    ) { isDragging ->
                        val elevation by androidx.compose.animation.core.animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        
                        Surface(
                            color = if (isCustom) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = elevation,
                            modifier = Modifier.clickable {
                                if (!isEditMode) {
                                    onSendMessage(msg)
                                    onDismiss()
                                }
                            }.then(if (isEditMode) Modifier.draggableHandle() else Modifier)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCustom) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                
                                if (isEditMode) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove Preset",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                val updatedList = currentPresets.filter { it != msg }
                                                currentPresets = updatedList
                                                savedCustomPresetsStr = updatedList.joinToString("|||")
                                            },
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Common Playback Controls",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    count = commonPlaybackPresets.size,
                    key = { index -> "common_${commonPlaybackPresets[index]}" }
                ) { index ->
                    val msg = commonPlaybackPresets[index]
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            onSendMessage(msg)
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ReactionRow(onEmojiSelected: (String) -> Unit, onShowEmojiPicker: (() -> Unit)? = null) {
    val emojis = listOf("❤️", "🔥", "🙌", "😭", "😮", "🕺", "✨", "💯")
    val neumorphicColors = com.github.musicyou.ui.styling.rememberNeumorphicColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neumorphicBasin(cornerRadius = 24.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            emojis.forEach { emoji ->
                Text(
                    text = emoji,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onEmojiSelected(emoji) }
                        .padding(4.dp)
                )
            }
            if (onShowEmojiPicker != null) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "More Emojis",
                    tint = Color.Gray,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onShowEmojiPicker() }
                        .padding(4.dp)
                        .size(24.dp)
                )
            }
        }
    }
}

@Composable
fun FlashMessageRow(
    contextType: String,
    onMessageSelected: (String) -> Unit,
    onOpenHub: (() -> Unit)? = null
) {
    val savedCustomPresetsStr by com.github.musicyou.utils.observePreference("saved_flash_presets_$contextType", "")
    val defaultPresets = getDefaultFlashMessages(contextType)
    val savedCustomPresets = remember(savedCustomPresetsStr) {
        if (savedCustomPresetsStr.isBlank()) defaultPresets
        else {
            val loaded = savedCustomPresetsStr.split("|||").filter { it.isNotBlank() }
            loaded + defaultPresets.filter { !loaded.contains(it) }
        }
    }
    
    val messages = remember(savedCustomPresets) {
        listOf("Custom Msg ⚡") + savedCustomPresets
    }

    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp)
    ) {
        items(messages.size) { index ->
            val msg = messages[index]
            val isCustomTrigger = msg == "Custom Msg ⚡"
            val bg = if (isCustomTrigger) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (isCustomTrigger) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

            Surface(
                color = bg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { 
                    if (isCustomTrigger && onOpenHub != null) {
                        onOpenHub()
                    } else {
                        onMessageSelected(msg)
                    }
                }
            ) {
                Text(
                    text = msg,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (isCustomTrigger) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}


private fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.size(scale.dp) // Dummy implementation to satisfy compiler if needed or use proper graphicsLayer
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun FullscreenControls(
    areControlsVisible: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    isYouTube: Boolean = false,
    onExitFullscreen: () -> Unit,
    position: Long,
    duration: Long,
    onShowSubtitles: () -> Unit,
    onShowAudio: () -> Unit,
    onLockClick: () -> Unit,
    activeSpeed: Float,
    onCycleSpeed: () -> Unit,
    onShowQuality: () -> Unit,
    videoQualityText: String,
    videoResizeMode: Int,
    onToggleZoom: () -> Unit,
    syncSessionState: SessionState? = null,
    currentDrift: Long? = null,
    currentMediaId: String? = null,
    isLocalVideo: Boolean = false,
    onShowFlashMessages: (() -> Unit)? = null,
    onShowEmojiPicker: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current

    // ── Glass Design Tokens ──
    val glassColor = Color.White.copy(alpha = 0.12f)
    val glassBorder = Color.White.copy(alpha = 0.2f)
    val glassShape = RoundedCornerShape(12.dp)
    val pillShape = RoundedCornerShape(16.dp)
    val accentRed = Color(0xFFFF3B30)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.35f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // ══════════════════════════════════════════════
        // TOP ROW
        // ══════════════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // ── Left: Status Pill + Flash & Emoji Row BELOW ──
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (syncSessionState != null && syncSessionState.sessionId != null) {
                    DynamicSyncStatusPill(
                        syncSessionState = syncSessionState,
                        currentDrift = currentDrift,
                        currentMediaId = currentMediaId,
                        isLocalVideo = isLocalVideo
                    )

                    if (onShowFlashMessages != null || onShowEmojiPicker != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (onShowFlashMessages != null) {
                                Box(
                                    modifier = Modifier
                                        .background(glassColor, pillShape)
                                        .border(1.dp, glassBorder, pillShape)
                                        .clip(pillShape)
                                        .clickable { onShowFlashMessages() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FlashOn,
                                            contentDescription = "Flash Messages",
                                            tint = Color(0xFFFFD54F),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Flash Message",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            if (onShowEmojiPicker != null) {
                                Box(
                                    modifier = Modifier
                                        .background(glassColor, pillShape)
                                        .border(1.dp, glassBorder, pillShape)
                                        .clip(pillShape)
                                        .clickable { onShowEmojiPicker() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "😃",
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Emoji Reaction",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Right: Subtitles (CC), Audio, Exit Fullscreen ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(glassColor, glassShape)
                        .border(1.dp, glassBorder, glassShape)
                        .clip(glassShape)
                        .clickable { onShowSubtitles() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ClosedCaption,
                        contentDescription = "Subtitles",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(glassColor, glassShape)
                        .border(1.dp, glassBorder, glassShape)
                        .clip(glassShape)
                        .clickable { onShowAudio() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Audiotrack,
                        contentDescription = "Audio Track",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(glassColor, glassShape)
                        .border(1.dp, glassBorder, glassShape)
                        .clip(glassShape)
                        .clickable { onExitFullscreen() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ══════════════════════════════════════════════
        // CENTER CONTROLS
        // ══════════════════════════════════════════════
        if (binder != null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(glassColor, CircleShape)
                        .border(1.dp, glassBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable { binder.syncSkipPrevious() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Play/Pause
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .clip(CircleShape)
                        .clickable { if (isPlaying) binder.syncPause() else binder.syncPlay() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // Next
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(glassColor, CircleShape)
                        .border(1.dp, glassBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable { binder.syncSkipNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // ══════════════════════════════════════════════
        // BOTTOM SECTION (Actions Row + Seekbar)
        // ══════════════════════════════════════════════
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        ) {
            // ── Bottom-right action row ──
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isYouTube) {
                    PlayerControlButton(
                        text = videoQualityText,
                        subText = "Quality",
                        onClick = onShowQuality
                    )
                }

                PlayerControlButton(
                    text = "${activeSpeed}x",
                    subText = "Speed",
                    onClick = onCycleSpeed
                )

                PlayerControlButton(
                    text = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else "Fit",
                    onClick = onToggleZoom
                )

                PlayerControlButton(
                    icon = Icons.Rounded.ScreenRotation,
                    onClick = {
                        val activity = context.findActivity()
                        if (activity != null) {
                            if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                                activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            ) {
                                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    }
                )

                PlayerControlButton(
                    text = "Lock",
                    onClick = onLockClick
                )
            }

            // ── Seekbar Row: Timestamp | Track | Timestamp ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatDuration(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f)
                )

                val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

                // Sleek thin Canvas seekbar
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                                binder?.syncSeekTo((ratio * duration).toLong())
                            }
                        }
                ) {
                    val trackY = center.y
                    val trackHeight = 2.dp.toPx()
                    val thumbRadius = 5.dp.toPx()
                    val progressX = progress * size.width

                    // Background track
                    drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = androidx.compose.ui.geometry.Offset(0f, trackY),
                        end = androidx.compose.ui.geometry.Offset(size.width, trackY),
                        strokeWidth = trackHeight,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )

                    // Active (played) track
                    if (progressX > 0f) {
                        drawLine(
                            color = accentRed,
                            start = androidx.compose.ui.geometry.Offset(0f, trackY),
                            end = androidx.compose.ui.geometry.Offset(progressX, trackY),
                            strokeWidth = trackHeight,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }

                    // Thumb dot
                    drawCircle(
                        color = accentRed,
                        radius = thumbRadius,
                        center = androidx.compose.ui.geometry.Offset(
                            progressX.coerceIn(thumbRadius, size.width - thumbRadius),
                            trackY
                        )
                    )
                }

                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun ThinSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Colors matching your HTML example
    val cardColor = Color(0xFFE6E5E4)      // #E6E5E4
    val trackOff = Color(0xFFBDBDBD)       // grey off track
    val trackOn = Color(0xFF4CAF50)        // green on track
    val thumb = Color(0xFFFFFFFF)          // white thumb
    val border = Color(0xFFB0B0B0)         // subtle border grey

    val trackColor by animateColorAsState(
        targetValue = if (checked) trackOn else trackOff,
        label = "trackColor"
    )

    val thumbColor by animateColorAsState(
        targetValue = thumb,
        label = "thumbColor"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .width(42.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(
                width = 1.dp,
                color = border.copy(alpha = 0.6f),
                shape = RoundedCornerShape(50)
            )
            .then(
                if (enabled) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onCheckedChange(!checked) }
                else Modifier
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
fun ManualMatchOverlay(
    sessionState: com.github.musicyou.sync.session.SessionState,
    sessionManager: com.github.musicyou.sync.session.SessionManager
) {
    if (!sessionState.isPendingManualMatch) return

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            sessionManager.setManualMatchUri(uri.toString())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) { // Block touches from passing through
                detectTapGestures { } 
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Match Required",
                tint = Color.Yellow,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Manual Match Required",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A local file was played but couldn't be matched automatically. Please select it from your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            androidx.compose.material3.Button(
                onClick = { launcher.launch("*/*") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Folder, contentDescription = "Select File")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Local File")
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { sessionManager.cancelManualMatch() }
            ) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}

@Composable
fun PlayerControlButton(
    text: String = "",
    subText: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColor = Color.White.copy(alpha = 0.12f)
    val glassBorder = Color.White.copy(alpha = 0.2f)
    val pillShape = RoundedCornerShape(16.dp)
    
    Box(
        modifier = modifier
            .size(if (icon != null && text.isEmpty()) 44.dp else 64.dp, 44.dp)
            .background(glassColor, pillShape)
            .border(1.dp, glassBorder, pillShape)
            .clip(pillShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null && text.isEmpty()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        } else if (subText != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Text(
                    text = subText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        } else {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
    }
}
