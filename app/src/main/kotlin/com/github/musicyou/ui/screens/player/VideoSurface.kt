package com.github.musicyou.ui.screens.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.github.musicyou.ui.screens.player.components.DoubleTapSeekOverlay
import com.github.musicyou.ui.screens.player.components.VolumeBrightnessOverlay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import androidx.compose.foundation.gestures.calculateZoom
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.videoResizeModeKey
import com.github.musicyou.utils.doubleTapSeekDurationKey
import com.github.musicyou.utils.scrubSeekIntensityKey
import com.github.musicyou.utils.fastForwardSpeedTopKey
import com.github.musicyou.utils.fastForwardSpeedMidKey
import com.github.musicyou.utils.fastForwardSpeedBotKey

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onGestureActive: (Boolean) -> Unit = {},
    onRewindEnd: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    isLocked: Boolean = false,
    onLockedChange: (Boolean) -> Unit = {},
    baselineSpeed: Float = 1f
) {
    // Gesture State
    var isBoosting by remember { mutableStateOf(false) }
    var isRewinding by remember { mutableStateOf(false) }
    var isLockControlsVisible by remember { mutableStateOf(false) } // For unlocking
    
    // Feedback State
    var seekRippleState by remember { mutableStateOf<SeekRippleData?>(null) }
    var playPauseState by remember { mutableStateOf<PlayPauseData?>(null) }
    var zoomToastState by remember { mutableStateOf<String?>(null) } 
    
    // Preferences
    val prefResizeMode by rememberPreference(videoResizeModeKey, AspectRatioFrameLayout.RESIZE_MODE_FIT)
    val doubleTapSeekDuration by rememberPreference(doubleTapSeekDurationKey, 10)
    val scrubSeekIntensity by rememberPreference(scrubSeekIntensityKey, 1.0f)
    val ffTopSpeed by rememberPreference(fastForwardSpeedTopKey, 3.0f)
    val ffMidSpeed by rememberPreference(fastForwardSpeedMidKey, 2.0f)
    val ffBotSpeed by rememberPreference(fastForwardSpeedBotKey, 1.5f)
    // Zoom / Resize State
    var resizeMode by remember { mutableStateOf(prefResizeMode) }
    
    // Sync initial preference if changed externally
    LaunchedEffect(prefResizeMode) {
        resizeMode = prefResizeMode
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    // Default 50% brightness
    LaunchedEffect(Unit) {
        activity?.window?.attributes = activity?.window?.attributes?.apply {
            if (screenBrightness < 0) {
                screenBrightness = 0.5f
            }
        }
    }
    
    // Loudness Enhancer for 200% volume
    val exoPlayer = player as? androidx.media3.exoplayer.ExoPlayer
    var loudnessEnhancer by remember { mutableStateOf<android.media.audiofx.LoudnessEnhancer?>(null) }
    
    DisposableEffect(exoPlayer?.audioSessionId) {
        val sessionId = exoPlayer?.audioSessionId ?: androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
        if (sessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId).apply {
                    enabled = true
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
        
        onDispose {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        }
    }
    var currentSoftwareBoost by remember { mutableFloatStateOf(0f) }

    var currentVolume by remember { mutableStateOf<Float?>(null) }
    var currentBrightness by remember { mutableStateOf<Float?>(null) }
    
    // Tap Timing State
    var lastTapTime by remember { mutableStateOf(0L) }
    var tapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val tapScope = androidx.compose.runtime.rememberCoroutineScope() // Controls visibility of the big lock icon
    
    var currentSpeedMultiplier by remember { mutableFloatStateOf(2.0f) } 
    
    // Y-Tracking for Zone Detection
    var activeZoneIndex by remember { mutableStateOf(1) } 

    // Safely reset speed/state if disposed
    DisposableEffect(Unit) {
        onDispose {
            if (isBoosting || isLocked) {
                onSpeedChange(baselineSpeed)
            }
        }
    }

    // Reset Speed/State on Unlock
    LaunchedEffect(isLocked) {
         if (!isLocked) {
             isBoosting = false
             isRewinding = false
             isLockControlsVisible = false
             onSpeedChange(baselineSpeed)
         } else {
             isLockControlsVisible = true
         }
    }

    LaunchedEffect(isLockControlsVisible, isLocked) {
        if (isLockControlsVisible && isLocked) {
            delay(3000)
            isLockControlsVisible = false
        }
    }

    // Rewind Loop Logic
    LaunchedEffect(isRewinding, currentSpeedMultiplier, isLocked) {
        if (isRewinding || (isLocked && isRewinding)) { 
            if (player.isPlaying) player.pause()
            
            while (isActive) {
                val rewindStep = (currentSpeedMultiplier * 100).toLong() 
                val target = player.currentPosition - rewindStep
                player.seekTo(target.coerceAtLeast(0))
                delay(30) 
            }
        } else if (!isBoosting && !isRewinding && !isLocked) {
             // Let the player resume normal state, but DO NOT auto-toggle play/pause
        }
    }

    // Forward Speed Logic
    LaunchedEffect(isBoosting, currentSpeedMultiplier, isLocked) {
        if (isBoosting || (isLocked && isBoosting)) {
            if (player.playbackParameters.speed != currentSpeedMultiplier) {
                onSpeedChange(currentSpeedMultiplier)
            }
        } else if (!isRewinding && !isLocked) {
             if (player.playbackParameters.speed != baselineSpeed) {
                onSpeedChange(baselineSpeed)
            }
        }
    }
    

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Video Layer
        AndroidView<PlayerView>(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    isClickable = false
                    isFocusable = false
                    resizeMode = resizeMode
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.player != player) {
                    playerView.player = player
                }
                if (playerView.resizeMode != resizeMode) {
                    playerView.resizeMode = resizeMode
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 2. Gesture Detection Layer
        val viewConfiguration = LocalViewConfiguration.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        
                        
                        // LOCKED STATE HANDLING
                        if (isLocked) {
                            down.consume()
                            isLockControlsVisible = true
                            // Consume all subsequent events to block gestures
                             do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        
                        val startX = down.position.x
                        val startY = down.position.y
                        val screenWidth = size.width
                        val screenHeight = size.height
                        
                        // Determine Zone
                        val isLeftZone = startX < screenWidth / 2
                        
                        // Initial Speed Zone
                        val zoneHeight = screenHeight / 3
                        activeZoneIndex = (startY / zoneHeight).toInt().coerceIn(0, 2)
                        
                        // PINCH DETECTION CHECK
                        // Just consume the down initially so we track it
                        down.consume()
                        
                        // --- TAP / PINCH DETECTION PHASE ---
                        var isTap = false
                        var isDrag = false
                        var isPinch = false
                        var dragType = 0 // 1: Vertical, 2: Horizontal
                        val longPressTimeout = 250L
                        
                        try {
                            withTimeout(longPressTimeout) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    
                                    // 1. PINCH CHECK
                                    if (event.changes.size > 1) {
                                         isPinch = true
                                         throw kotlinx.coroutines.CancellationException("Pinch detected")
                                    }
                                    
                                    val change = event.changes.firstOrNull() ?: break
                                    
                                    if (!change.pressed) {
                                        // RELEASED before timeout -> TAP
                                        isTap = true
                                        change.consume()
                                        throw kotlinx.coroutines.CancellationException("Tap detected") 
                                    }
                                    
                                    val dragAmount = change.position - down.position
                                    if (dragAmount.getDistance() > viewConfiguration.touchSlop) {
                                        // MOVED > SLOP -> DRAG
                                        isDrag = true
                                        if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                                            dragType = 1
                                        } else {
                                            dragType = 2
                                        }
                                        change.consume()
                                        throw kotlinx.coroutines.CancellationException("Drag detected") 
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Timeout or Cancellation
                        }
                        
                        // --- HANDLE PINCH ---
                        if (isPinch) {
                            var zoomFactor = 1f
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom() 
                                zoomFactor *= zoomChange
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            
                            // Apply Zoom Threshold
                            if (zoomFactor > 1.1f) {
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                zoomToastState = "Zoomed to Fill"
                            } else if (zoomFactor < 0.9f) {
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                zoomToastState = "Fit to Screen"
                            }
                            // Clear toast after delay
                             tapScope.launch {
                                delay(2000)
                                if (zoomToastState != null) zoomToastState = null
                            }
                            return@awaitEachGesture
                        }
                        
                        // --- HANDLE TAP ---
                        if (isTap) {
                            val currentTime = System.currentTimeMillis()
                            val isDoubleTap = (currentTime - lastTapTime) < 300
                            
                            if (isDoubleTap) {
                                // DOUBLE TAP -> SEEK
                                tapJob?.cancel() // Cancel pending play/pause
                                val seekDurationMs = doubleTapSeekDuration * 1000L
                                if (isLeftZone) {
                                    // Rewind
                                    onSeek((player.currentPosition - seekDurationMs).coerceAtLeast(0))
                                    seekRippleState = SeekRippleData(isForward = false)
                                } else {
                                    // Forward
                                    onSeek((player.currentPosition + seekDurationMs).coerceAtMost(player.duration))
                                    seekRippleState = SeekRippleData(isForward = true)
                                }
                                // Clear ripple after animation
                                tapScope.launch {
                                    delay(600)
                                    seekRippleState = null
                                }
                                lastTapTime = 0L 
                            } else {
                                // SINGLE TAP -> TOGGLE CONTROLS (NO PLAY/PAUSE)
                                lastTapTime = currentTime
                                tapJob = tapScope.launch {
                                    delay(300) // Wait for potential second tap
                                    onTap()
                                }
                            }
                            return@awaitEachGesture
                        }
                        
                        // --- HOLD / DRAG PHASE ---
                        // If we are here, it's either a TIMEOUT (Hold) or a DRAG start
                        
                        if (dragType == 1) {
                            // Vertical Drag -> Volume/Brightness
                            var totalDragY = 0f
                            val maxDragDistance = screenHeight / 2f
                            
                            var startVolume = 0f
                            var startBrightness = 0f
                            
                            if (isLeftZone) {
                                startBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                                if (startBrightness < 0) {
                                    try {
                                        startBrightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                                    } catch (e: Exception) {
                                        startBrightness = 0.5f
                                    }
                                }
                                currentBrightness = startBrightness
                            } else {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                val hardwareVol = if (maxVol > 0) currentVol / maxVol else 0f
                                
                                startVolume = if (hardwareVol >= 1f) {
                                    1f + currentSoftwareBoost
                                } else {
                                    currentSoftwareBoost = 0f
                                    hardwareVol
                                }
                                currentVolume = startVolume
                            }
                            
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val deltaY = change.positionChange().y
                                    totalDragY += deltaY
                                    val changePercent = -totalDragY / maxDragDistance
                                    
                                    if (isLeftZone) {
                                        val newBrightness = (startBrightness + changePercent).coerceIn(0f, 1f)
                                        currentBrightness = newBrightness
                                        activity?.window?.attributes = activity?.window?.attributes?.apply { 
                                            screenBrightness = newBrightness 
                                        }
                                    } else {
                                        val newVolume = (startVolume + changePercent).coerceIn(0f, 2f)
                                        currentVolume = newVolume
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        
                                        if (newVolume <= 1f) {
                                            currentSoftwareBoost = 0f
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (newVolume * maxVol).roundToInt(), 0)
                                            try { loudnessEnhancer?.setTargetGain(0) } catch(e: Exception) {}
                                        } else {
                                            currentSoftwareBoost = newVolume - 1f
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                                            try { loudnessEnhancer?.setTargetGain((currentSoftwareBoost * 1500).toInt()) } catch(e: Exception) {}
                                        }
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            
                            currentBrightness = null
                            currentVolume = null
                            
                        } else if (dragType == 2) {
                            // Horizontal Drag -> Seek / Scrub
                            var totalDragX = 0f
                            val maxDragDistance = screenWidth.toFloat()
                            val startPosition = player.currentPosition
                            val videoDuration = player.duration.coerceAtLeast(1L)
                            
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val deltaX = change.positionChange().x
                                    totalDragX += deltaX
                                    
                                    // Scale scrub based on video length
                                    val seekRangeMs = if (videoDuration > 600000L) {
                                        // > 10 min: full width = 10% of video
                                        videoDuration / 10f
                                    } else {
                                        // < 10 min: full width = 2 minutes (or duration)
                                        kotlin.math.min(120000f, videoDuration.toFloat())
                                    }
                                    // Apply user scrubSeekIntensity multiplier
                                    val seekMsPerPixel = (seekRangeMs / maxDragDistance) * scrubSeekIntensity
                                    
                                    val seekAmount = (totalDragX * seekMsPerPixel).toLong()
                                    val newPosition = (startPosition + seekAmount).coerceIn(0, videoDuration)
                                    
                                    onSeek(newPosition)
                                    
                                    // Feedback using toast
                                    val totalSeconds = newPosition / 1000
                                    val minutes = totalSeconds / 60
                                    val seconds = totalSeconds % 60
                                    val timeString = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                                    
                                    val diff = newPosition - startPosition
                                    val sign = if (diff >= 0) "+" else "-"
                                    val diffSeconds = kotlin.math.abs(diff) / 1000
                                    val diffMins = diffSeconds / 60
                                    val diffSecs = diffSeconds % 60
                                    val diffString = "${sign}${diffMins.toString().padStart(2, '0')}:${diffSecs.toString().padStart(2, '0')}"
                                    
                                    zoomToastState = "$timeString  [$diffString]"
                                    
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            
                            // Clear toast after scrubbing
                            tapScope.launch {
                                delay(800)
                                if (zoomToastState?.contains("[") == true) {
                                    zoomToastState = null
                                }
                            }
                            
                        } else {
                            // Hold (dragType == 0) -> Speed Zone
                            currentSpeedMultiplier = when(activeZoneIndex) {
                                0 -> ffTopSpeed
                                1 -> ffMidSpeed
                                else -> ffBotSpeed
                            }
                            
                            
                            // baselineSpeed is used instead of originalSpeed (passed from Player.kt)
                            
                            if (isLeftZone) {
                                isRewinding = true
                            } else {
                                 isBoosting = true
                            }
                            onGestureActive(true)
                            
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { 
                                    if (it.pressed) {
                                        val currentY = it.position.y
                                        activeZoneIndex = (currentY / zoneHeight).toInt().coerceIn(0, 2)
                                        
                                         currentSpeedMultiplier = when(activeZoneIndex) {
                                            0 -> ffTopSpeed
                                            1 -> ffMidSpeed
                                            else -> ffBotSpeed
                                        }
                                        
                                        it.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
    
                            // Release
                            if (!isLocked) {
                                 if (isRewinding) {
                                    onRewindEnd()
                                }
                                isBoosting = false
                                isRewinding = false
                            }
                            onGestureActive(false)
                        }
                    }
                }
        )
        
        // 3. VISUAL FEEDBACK LAYERS
        
        // Seek Ripple
        seekRippleState?.let { data ->
            DoubleTapSeekOverlay(isForward = data.isForward)
        }
        
        currentVolume?.let { vol ->
            VolumeBrightnessOverlay(value = vol, isVolume = true)
        }
        
        currentBrightness?.let { bright ->
            VolumeBrightnessOverlay(value = bright, isVolume = false)
        }
        
        // Play/Pause Icon
        playPauseState?.let { data ->
             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                 Icon(
                     imageVector = if (data.isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                     contentDescription = null,
                     tint = Color.White.copy(alpha = 0.8f),
                     modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(16.dp)
                 )
             }
        }
        
        // Zoom Toast
        zoomToastState?.let { text ->
            Box(modifier = Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 4. Unlock Overlay
        if (isLockControlsVisible && isLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { 
                            onLockedChange(false)
                            isLockControlsVisible = false 
                        }
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.LockOpen,
                        contentDescription = "Unlock",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to Unlock",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Removed local corner lock button (moved to Player.kt)


        // 5. Edge Speed Overlays
        if (isBoosting) {
            EdgeSpeedOverlay(
                modifier = Modifier.align(Alignment.CenterEnd),
                activeZoneIndex = activeZoneIndex,
                isRewind = false
            )
        }
        if (isRewinding) {
            EdgeSpeedOverlay(
                modifier = Modifier.align(Alignment.CenterStart),
                activeZoneIndex = activeZoneIndex,
                isRewind = true
            )
        }
    }
}

data class SeekRippleData(val isForward: Boolean)
data class PlayPauseData(val isPlaying: Boolean)


@Composable
fun EdgeSpeedOverlay(
    modifier: Modifier = Modifier,
    activeZoneIndex: Int, 
    isRewind: Boolean
) {
    val speeds = listOf(3.0f, 2.0f, 1.5f)
    
    Column(
        modifier = modifier, 
        verticalArrangement = Arrangement.Center, 
        horizontalAlignment = if (isRewind) Alignment.Start else Alignment.End
    ) {
        speeds.forEachIndexed { index, speed ->
            val isSelected = index == activeZoneIndex
            val shape = if (!isRewind) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            }
            
            Box(
                modifier = Modifier
                    .width(100.dp) 
                    .padding(vertical = 2.dp) 
                    .clip(shape)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.25f) 
                        else Color.Black.copy(alpha = 0.4f)
                    )
                    .padding(horizontal = 24.dp, vertical = 24.dp) 
            ) {
                val alpha = if (isSelected) 1f else 0.6f
                val text = if (isRewind) "<< ${speed.toString().removeSuffix(".0")}x" 
                           else "${speed.toString().removeSuffix(".0")}x"

                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        shadow = if (isSelected) Shadow(Color.White, blurRadius = 15f) else Shadow(blurRadius = 0f)
                    ),
                    color = Color.White.copy(alpha = alpha),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun LockProgressOverlay(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Locking",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}
