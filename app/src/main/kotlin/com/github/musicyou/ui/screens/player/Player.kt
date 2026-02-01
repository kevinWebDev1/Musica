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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Timer
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.innertube.models.NavigationEndpoint
import com.github.musicyou.Database
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.R
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.ui.components.BaseMediaItemMenu
import com.github.musicyou.ui.components.TooltipIconButton
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
import com.github.musicyou.sync.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

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

    var shouldBePlaying by remember { mutableStateOf(binder.player.shouldBePlaying) }
    var nullableMediaItem by remember {
        mutableStateOf(
            binder.player.currentMediaItem,
            neverEqualPolicy()
        )
    }

    binder.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nullableMediaItem = mediaItem
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = binder.player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = binder.player.shouldBePlaying
            }
        }
    }

    val mediaItem = nullableMediaItem ?: return
    val positionAndDuration by binder.player.positionAndDurationState()
    val nextSongTitle =
        if (binder.player.hasNextMediaItem()) binder.player.getMediaItemAt(binder.player.nextMediaItemIndex).mediaMetadata.title.toString()
        else stringResource(id = R.string.open_queue)

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
    var isShowingSleepTimerDialog by rememberSaveable { mutableStateOf(false) }
    var isShowingSyncSheet by rememberSaveable { mutableStateOf(false) }
    var isShowingSpeedDialog by rememberSaveable { mutableStateOf(false) }
    
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
    var areControlsVisible by remember { mutableStateOf(false) }
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
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
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



        val isVideo = remember(mediaItem, syncSessionState.localMatchUri) {
            val path = syncSessionState.localMatchUri ?: mediaItem.mediaId
            path.endsWith(".mp4", ignoreCase = true) ||
                    path.endsWith(".mkv", ignoreCase = true) ||
                    path.endsWith(".mov", ignoreCase = true) ||
                    path.contains("video", ignoreCase = true)
        }

        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                            if (isVideo) {
                                Box(contentAlignment = Alignment.BottomEnd) {
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
                                        onSpeedChange = { binder.player.setPlaybackSpeed(it) }
                                    )
                                    if (!isFullScreen) {
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
                                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                            if (isVideo) {
                                Box(contentAlignment = Alignment.BottomEnd) {
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
                                        onSpeedChange = { binder.player.setPlaybackSpeed(it) }
                                    )
                                    if (!isFullScreen) {
                                        IconButton(
                                            onClick = { isFullScreen = true },
                                            modifier = Modifier
                                                .padding(if (isFullScreen) 16.dp else 24.dp)
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
                if (isFullScreen && (areControlsVisible || isSpeedGestureActive)) {
                    FullscreenControls(
                        areControlsVisible = areControlsVisible || isSpeedGestureActive,
                        player = binder.player,
                        onExitFullscreen = { isFullScreen = false },
                        position = positionAndDuration.first,
                        duration = positionAndDuration.second
                    )
                }
            }

            if (!isFullScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BottomSheetDefaults.ExpandedShape)
                        .clickable { isQueueOpen = true }
                        .background(neumorphicColors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 8.dp, vertical = 0.dp) // Thinner padding
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount < 0) isQueueOpen = true
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { isQueueOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = null
                        )
                    }

                    Text(
                        text = nextSongTitle,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1F),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )

                    TooltipIconButton(
                        description = R.string.sleep_timer,
                        onClick = { isShowingSleepTimerDialog = true },
                        icon = if (sleepTimerMillisLeft == null) Icons.Outlined.Timer else Icons.Filled.Timer
                    )

                    // Sync Icon with Active Indicator
                    Box {
                        TooltipIconButton(
                           description = R.string.sync_session,
                           onClick = { isShowingSyncSheet = true },
                           icon = Icons.Outlined.Group,
                           tint = if (syncSessionState.sessionId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    // Persistent Speed Selector
                    IconButton(onClick = { isShowingSpeedDialog = true }) {
                         Text(
                             text = "${binder.player.playbackParameters.speed}x",
                             style = MaterialTheme.typography.labelSmall,
                             fontWeight = FontWeight.Bold,
                             maxLines = 1
                         )
                    }

                    // Smart Local Source Indicator
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
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = null,
                        )
                    }
                }
            }
        }

        if (!isFullScreen) {
            IconButton(
                onClick = onPop,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 32.dp, top = 8.dp)
                    .background(neumorphicColors.background.copy(alpha = 0.7f), shape = CircleShape)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Dismiss",
                    tint = neumorphicColors.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (isShowingSleepTimerDialog) {
            SleepTimer(
                sleepTimerMillisLeft = sleepTimerMillisLeft,
                onDismiss = { isShowingSleepTimerDialog = false }
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
        
        // SOCIAL: Top layer (Floating Emojis & Cards)
        Box(modifier = Modifier.fillMaxSize().zIndex(100f)) {
            ReactionOverlay(event = lastReaction)
            FlashMessageOverlay(event = lastFlashMessage)
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
    val messages = listOf(
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

            val bg = MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = MaterialTheme.colorScheme.onSurface

            Surface(
                color = bg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { onMessageSelected(msg) }
            ) {
                Text(
                    text = msg,
                    color = fg,
                    fontSize = 12.sp,
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

@Composable
fun FullscreenControls(
    areControlsVisible: Boolean,
    player: Player,
    onExitFullscreen: () -> Unit,
    position: Long,
    duration: Long,
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
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // TOP RIGHT ACTIONS
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Rotation
            IconButton(
                onClick = { 
                     val activity = context.findActivity()
                     if (activity != null) {
                         if (activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || 
                             activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                             activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            // Exit
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
                     player.seekTo((ratio * duration).toLong())
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
