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
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Speed
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
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.utils.DisposableListener
import com.github.musicyou.utils.isLandscape
import com.github.musicyou.utils.positionAndDurationState
import com.github.musicyou.utils.seamlessPlay
import com.github.musicyou.utils.shouldBePlaying
import com.github.musicyou.ui.screens.player.social.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.media3.common.util.UnstableApi
import com.github.musicyou.sync.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

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
    
    // DEBUG: Ping tracker (Display integrated into status bar)
    val currentPing = syncSessionState.ping
    
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
                    .padding(bottom = 4.dp)
            ) {
                 Row(
                     verticalAlignment = Alignment.CenterVertically,
                     horizontalArrangement = Arrangement.Center
                 ) {
                     val count = syncSessionState.connectedPeers.size
                     val pingText = if (currentPing != null && currentPing > 0) "📍 ${currentPing}ms" else ""
                     val pingColor =
                         if (currentPing != null && currentPing > 800)
                             MaterialTheme.colorScheme.error
                         else
                             Color(0xFF2E7D32)


                     if (syncSessionState.isHost) {
                         Icon(
                             imageVector = Icons.Filled.FiberManualRecord,
                             contentDescription = "Live",
                             tint = Color.Red,
                             modifier = Modifier.size(10.dp)
                         )
                          Spacer(modifier = Modifier.width(6.dp))
                          
                          // HOST VIEW: Live Session: [GroupIcon] [Count] [Ping]
                          Text(
                              text = "Live Session:",
                              style = MaterialTheme.typography.labelMedium,
                              color = MaterialTheme.colorScheme.primary
                          )
                          Spacer(modifier = Modifier.width(4.dp))
                          Icon(
                              imageVector = Icons.Outlined.Group,
                              contentDescription = "Participants",
                              tint = MaterialTheme.colorScheme.primary,
                              modifier = Modifier.size(14.dp) // Slightly larger to match text
                          )
                          Spacer(modifier = Modifier.width(4.dp))
                          Text(
                              text = "$count",
                              style = MaterialTheme.typography.labelMedium,
                              color = MaterialTheme.colorScheme.primary
                          )

                          if (pingText.isNotEmpty()) {
                              Spacer(modifier = Modifier.width(8.dp))
                              Text(
                                  text = pingText,
                                  style = MaterialTheme.typography.labelMedium,
                                  color = pingColor
                              )
                          }
                      } else {
                          Icon(
                              imageVector = Icons.Filled.Link,
                              contentDescription = "Linked",
                              tint = MaterialTheme.colorScheme.primary,
                              modifier = Modifier.size(14.dp)
                          )
                           Spacer(modifier = Modifier.width(6.dp))
                           
                          // GUEST VIEW: Synced with Host [Icon] [Count] [Ping]
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = Modifier.basicMarquee()
                          ) {
                              Text(
                                 text = "Synced with Host",
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.primary
                              )
                              Spacer(modifier = Modifier.width(8.dp))
                              Icon(
                                  imageVector = Icons.Outlined.Group,
                                  contentDescription = "Participants",
                                  tint = MaterialTheme.colorScheme.primary,
                                  modifier = Modifier.size(14.dp)
                              )
                              Spacer(modifier = Modifier.width(4.dp))
                              Text(
                                  text = "$count",
                                  style = MaterialTheme.typography.labelMedium,
                                  color = MaterialTheme.colorScheme.primary
                              )
                              if (pingText.isNotEmpty()) {
                                  Spacer(modifier = Modifier.width(8.dp))
                                  Text(
                                      text = pingText,
                                      style = MaterialTheme.typography.labelMedium,
                                      color = pingColor
                                  )
                              }
                          }
                      }
                 }
            }
        }
    }
    
    // --- NEW: Host-Only Toggle (Bottom) ---
    val hostOnlyToggleContent: @Composable () -> Unit = {
        if (syncSessionState.sessionId != null && syncSessionState.isHost) {
             Box(
                 modifier = Modifier
                     .fillMaxWidth()
                     .padding(horizontal = 24.dp)
                     .padding(top = 8.dp)
             ) {
                 Row(
                     verticalAlignment = Alignment.CenterVertically,
                     modifier = Modifier
                         .fillMaxWidth()
                         // Use Grey background as requested for visibility
                         .background(Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                         // Remove border as background is now distinct
                         .clickable { binder.sessionManager.setHostOnlyMode(!syncSessionState.hostOnlyMode) }
                         .padding(horizontal = 16.dp, vertical = 6.dp)
                 ) {
                     Text(
                         text = "Host-Only Mode",
                         style = MaterialTheme.typography.bodyMedium,
                         // Force correct contrast against LightGray
                         color = MaterialTheme.colorScheme.onSurface, 
                         modifier = Modifier.weight(1f)
                     )
                     ThinSwitch(
                         checked = syncSessionState.hostOnlyMode,
                         onCheckedChange = { binder.sessionManager.setHostOnlyMode(it) }
                     )
                 }
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
                isLocked = isParticipantLocked,
                modifier = Modifier.weight(1f)
            )

            // SOCIAL: Interaction Triggers (Always visible in Sync Session, even if Locked)
            if (sessionManager != null && syncSessionState.sessionId != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ReactionRow(onEmojiSelected = { sessionManager.sendReaction(it) })
                Spacer(modifier = Modifier.height(8.dp))
                FlashMessageRow(onMessageSelected = { sessionManager.sendFlashMessage(it) })
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Inject Host-Only Toggle below Controls
            hostOnlyToggleContent()
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
        val isVideo = remember(mediaItem, syncSessionState.localMatchUri, mediaItem.mediaMetadata.extras?.getBoolean("forceVideo"), isYouTube) {
            val path = syncSessionState.localMatchUri ?: mediaItem.mediaId
            val isVid = isYouTube || 
                    path.endsWith(".mp4", ignoreCase = true) ||
                    path.endsWith(".mkv", ignoreCase = true) ||
                    path.endsWith(".mov", ignoreCase = true) ||
                    path.contains("video", ignoreCase = true) ||
                    mediaItem.mediaMetadata.extras?.getBoolean("forceVideo") == true
            
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
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp)
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
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Rotation Button
                                            IconButton(
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
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ScreenRotation,
                                                    contentDescription = "Rotate Screen",
                                                    tint = Color.White
                                                )
                                            }
                                            
                                            // Fullscreen Button
                                            IconButton(
                                                onClick = { isFullScreen = true },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fullscreen,
                                                    contentDescription = "Enter Fullscreen",
                                                    tint = Color.White
                                                )
                                            }

                                            // Lock Button
                                            IconButton(
                                                onClick = { isLocked = !isLocked },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Default.LockOpen,
                                                    contentDescription = "Lock",
                                                    tint = Color.White
                                                )
                                            }
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
                                            binder.player.setPlaybackSpeed(it) 
                                        },
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        },
                                        baselineSpeed = baselineSpeed
                                    )
                                    if (!isFullScreen) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Quality Toggle
                                            IconButton(
                                                onClick = { isShowingQualityDialog = true },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                val qualityText = when (videoQuality) {
                                                    com.github.musicyou.enums.VideoQuality.AUTO -> "Auto"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_360P -> "360p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_720P -> "720p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> "1080p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> "1440p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> "2160p"
                                                }
                                                Text(
                                                    text = qualityText,
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Rotation Button
                                            IconButton(
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
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ScreenRotation,
                                                    contentDescription = "Rotate Screen",
                                                    tint = Color.White
                                                )
                                            }
                                            
                                            // Zoom/Fit Toggle
                                            IconButton(
                                                onClick = {
                                                    videoResizeMode = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Crop,
                                                    contentDescription = "Toggle Zoom",
                                                    tint = Color.White
                                                )
                                            }

                                            // Fullscreen Button
                                            IconButton(
                                                onClick = { isFullScreen = true },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fullscreen,
                                                    contentDescription = "Enter Fullscreen",
                                                    tint = Color.White
                                                )
                                            }

                                            // Speed Toggle
                                            IconButton(
                                                onClick = {
                                                    val currentIndex = availableSpeeds.indexOf(baselineSpeed)
                                                    val nextIndex = (currentIndex + 1) % availableSpeeds.size
                                                    baselineSpeed = availableSpeeds[nextIndex]
                                                    activeSpeed = baselineSpeed
                                                    binder.player.setPlaybackSpeed(baselineSpeed)
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Text(
                                                    text = "${baselineSpeed}x",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Lock Button
                                            IconButton(
                                                onClick = { isLocked = !isLocked },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Default.LockOpen,
                                                    contentDescription = "Lock",
                                                    tint = Color.White
                                                )
                                            }
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
                        modifier = Modifier.windowInsetsPadding(if (isFullScreen) WindowInsets(0, 0, 0, 0) else WindowInsets.statusBars)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(
                                if (isFullScreen) 1f 
                                else if (syncSessionState.sessionId != null) 0.9f // Less weight for art in sync to see controls
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
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Rotation Button
                                            IconButton(
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
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ScreenRotation,
                                                    contentDescription = "Rotate Screen",
                                                    tint = Color.White
                                                )
                                            }

                                            // Lock Button
                                            IconButton(
                                                onClick = { isLocked = !isLocked },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Default.LockOpen,
                                                    contentDescription = "Lock",
                                                    tint = Color.White
                                                )
                                            }

                                            // Fullscreen Button
                                            IconButton(
                                                onClick = { isFullScreen = true },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fullscreen,
                                                    contentDescription = "Enter Fullscreen",
                                                    tint = Color.White
                                                )
                                            }
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
                                            binder.player.setPlaybackSpeed(it) 
                                        },
                                        isLocked = isLocked,
                                        onLockedChange = { 
                                            isLocked = it
                                            if (!it) areControlsVisible = true
                                        },
                                        baselineSpeed = baselineSpeed
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
                                            IconButton(
                                                onClick = { showSubtitlesSheet = true },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Subtitles,
                                                    contentDescription = "Subtitles",
                                                    tint = Color.White
                                                )
                                            }
                                            
                                            // Audio Track
                                            IconButton(
                                                onClick = { showAudioSheet = true },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Audiotrack,
                                                    contentDescription = "Audio Track",
                                                    tint = Color.White
                                                )
                                            }
                                        }

                                        // Bottom Right Controls (Rotate, Lock, Fullscreen)
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(bottom = 16.dp, end = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Quality Toggle
                                            IconButton(
                                                onClick = { isShowingQualityDialog = true },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                val qualityText = when (videoQuality) {
                                                    com.github.musicyou.enums.VideoQuality.AUTO -> "Auto"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_360P -> "360p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_720P -> "720p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> "1080p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> "1440p"
                                                    com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> "2160p"
                                                }
                                                Text(
                                                    text = qualityText,
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Rotation Button
                                            IconButton(
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
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ScreenRotation,
                                                    contentDescription = "Rotate Screen",
                                                    tint = Color.White
                                                )
                                            }

                                            // Speed Toggle
                                            IconButton(
                                                onClick = {
                                                    val currentIndex = availableSpeeds.indexOf(baselineSpeed)
                                                    val nextIndex = (currentIndex + 1) % availableSpeeds.size
                                                    baselineSpeed = availableSpeeds[nextIndex]
                                                    activeSpeed = baselineSpeed
                                                    binder.player.setPlaybackSpeed(baselineSpeed)
                                                },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Text(
                                                    text = "${baselineSpeed}x",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Lock Button
                                            IconButton(
                                                onClick = { isLocked = !isLocked },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Default.LockOpen,
                                                    contentDescription = "Lock",
                                                    tint = Color.White
                                                )
                                            }

                                            // Zoom/Fit Toggle
                                            IconButton(
                                                onClick = {
                                                    videoResizeMode = if (videoResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                },
                                                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Crop,
                                                    contentDescription = "Toggle Zoom",
                                                    tint = Color.White
                                                )
                                            }

                                            // Fullscreen Button
                                            IconButton(
                                                onClick = { isFullScreen = true },
                                                modifier = Modifier
                                                    .background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fullscreen,
                                                    contentDescription = "Enter Fullscreen",
                                                    tint = Color.White
                                                )
                                            }
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
                if (isFullScreen && (areControlsVisible || isSpeedGestureActive) && !isLocked) {
                    FullscreenControls(
                        areControlsVisible = areControlsVisible || isSpeedGestureActive,
                        isPlaying = shouldBePlaying,
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
                            binder.player.setPlaybackSpeed(baselineSpeed)
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
                        }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isQueueOpen = true }
                            .padding(horizontal = 8.dp, vertical = 0.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { _, dragAmount ->
                                        if (dragAmount < 0) isQueueOpen = true
                                    }
                                )
                            },
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playlist / Queue
                        IconButton(onClick = { isQueueOpen = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                contentDescription = null
                            )
                        }

                        // Sleep Timer
                        TooltipIconButton(
                            description = R.string.sleep_timer,
                            onClick = { isShowingSleepTimerDialog = true },
                            icon = if (sleepTimerMillisLeft == null) Icons.Outlined.Timer else Icons.Filled.Timer
                        )

                        // Sync Session (Middle)
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                )
                                .clickable { isShowingSyncSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Group, 
                                contentDescription = "Sync Session", 
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Download Button
                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                if (!isDownloaded && !isLocalItem) {
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

                        // Persistent Speed Selector (Icon instead of Text)
                        IconButton(onClick = { isShowingSpeedDialog = true }) {
                            Icon(imageVector = Icons.Outlined.Speed, contentDescription = "Speed")
                        }


                        
                        // Smart Local Source Indicator (Dynamic)
                        val hasLocalMatch = syncSessionState.localMatchUri != null
                        if (hasLocalMatch) {
                            val isActive = syncSessionState.clockSyncMessage?.contains("Local") == true
                            val sourceColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            val sourceIcon = if (isActive) Icons.Filled.Folder else Icons.Outlined.Folder
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
                                    binder.player.setPlaybackSpeed(speed)
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
fun ReactionRow(onEmojiSelected: (String) -> Unit) {
    val emojis = listOf("❤️", "🔥", "🙌", "😭", "😮", "🕺", "✨", "💯")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        emojis.forEach { emoji ->
            Text(
                text = emoji,
                fontSize = 20.sp, // Reduced from 24sp
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onEmojiSelected(emoji) }
                    .padding(4.dp) // Reduced from 6dp
            )
        }
    }
}

@Composable
fun FlashMessageRow(onMessageSelected: (String) -> Unit) {
    var showCustomMsgDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var customMsgText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    val messages = listOf(
        "Custom Msg 💬",
        "I LOVE this track 😭🔥",
        "Favorite song!!",
        "Skip this one 🙅",
        "Dancing rn 💃",
        "Singing along 🎤",
        "Next song pls ⏭️",
        "Hits different 🥺",
        "LOVE this part 😍"
    )
    
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp), // Reduced from 10dp
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp)
    ) {
        items(messages.size) { index ->
            val msg = messages[index]

            val isCustom = msg == "Custom Msg 💬"
            val bg = if (isCustom) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (isCustom) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

            Surface(
                color = bg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { 
                    if (isCustom) {
                        showCustomMsgDialog = true
                    } else {
                        onMessageSelected(msg)
                    }
                }
            ) {
                Text(
                    text = msg,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (isCustom) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }

    if (showCustomMsgDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCustomMsgDialog = false },
            title = { Text("Custom Message") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = customMsgText,
                    onValueChange = { customMsgText = it },
                    label = { Text("Type your message...") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = {
                            if (customMsgText.isNotBlank()) {
                                onMessageSelected(customMsgText)
                                showCustomMsgDialog = false
                                customMsgText = ""
                            }
                        }
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (customMsgText.isNotBlank()) {
                            onMessageSelected(customMsgText)
                            showCustomMsgDialog = false
                            customMsgText = ""
                        }
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCustomMsgDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.6f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.8f)
                )
            ))
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp) // Added top margin for vertical fullscreen
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // TOP RIGHT ACTIONS
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Subtitles
            IconButton(
                onClick = onShowSubtitles,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Subtitles,
                    contentDescription = "Subtitles",
                    tint = Color.White
                )
            }
            
            // Audio Track
            IconButton(
                onClick = onShowAudio,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Audiotrack,
                    contentDescription = "Audio Track",
                    tint = Color.White
                )
            }

            // Exit Fullscreen
            IconButton(
                onClick = onExitFullscreen,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FullscreenExit,
                    contentDescription = "Exit Fullscreen",
                    tint = Color.White
                )
            }
        }
        
        // BOTTOM RIGHT ACTIONS
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 56.dp), // Position above the seekbar
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quality Toggle
            IconButton(
                onClick = onShowQuality,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Text(
                    text = videoQualityText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Rotation
            IconButton(
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
                },
                 modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ScreenRotation,
                    contentDescription = "Rotate",
                    tint = Color.White
                )
            }

            // Zoom/Fit Toggle
            IconButton(
                onClick = onToggleZoom,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = if (videoResizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM) Icons.Outlined.Fullscreen else Icons.Outlined.Crop,
                    contentDescription = "Toggle Zoom",
                    tint = Color.White
                )
            }

            // Speed Toggle
            IconButton(
                onClick = onCycleSpeed,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Text(
                    text = "${activeSpeed}x",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Lock Screen
            IconButton(
                onClick = onLockClick,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Lock Controls",
                    tint = Color.White
                )
            }
        }
        
        // CENTER CONTROLS
        val binder = LocalPlayerServiceBinder.current
        if (binder != null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { binder.syncSkipPrevious() },
                    modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
                ) {
                    Icon(imageVector = Icons.Outlined.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                
                IconButton(
                    onClick = { if (isPlaying) binder.syncPause() else binder.syncPlay() },
                    modifier = Modifier.size(80.dp).background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause", 
                        tint = Color.White, 
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = { binder.syncSkipNext() },
                    modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
                ) {
                    Icon(imageVector = Icons.Outlined.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }

        // BOTTOM SEEKBAR
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
             Row(
                 modifier = Modifier.fillMaxWidth(),
                 horizontalArrangement = Arrangement.SpaceBetween,
                 verticalAlignment = Alignment.CenterVertically
             ) {
                 Text(
                     text = formatDuration(position),
                     style = MaterialTheme.typography.labelMedium,
                     color = Color.White
                 )
                 Text(
                     text = formatDuration(duration),
                     style = MaterialTheme.typography.labelMedium,
                     color = Color.White
                 )
             }
             
             Slider(
                 value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                 onValueChange = { ratio ->
                     binder?.syncSeekTo((ratio * duration).toLong())
                 },
                 colors = SliderDefaults.colors(
                     thumbColor = Color.White,
                     activeTrackColor = Color.White,
                     inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                 ),
                 modifier = Modifier.fillMaxWidth()
             )
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
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Folder, contentDescription = "Select File")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Local File")
            }
        }
    }
}
