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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import androidx.compose.foundation.gestures.calculateZoom
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onSpeedChange: (Float) -> Unit = {}
) {
    // Gesture State
    var isBoosting by remember { mutableStateOf(false) }
    var isRewinding by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var isLockControlsVisible by remember { mutableStateOf(false) } // For unlocking
    
    // Feedback State
    var seekRippleState by remember { mutableStateOf<SeekRippleData?>(null) }
    var playPauseState by remember { mutableStateOf<PlayPauseData?>(null) }
    var zoomToastState by remember { mutableStateOf<String?>(null) } 
    
    // Zoom / Resize State
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Tap Timing State
    var lastTapTime by remember { mutableStateOf(0L) }
    var tapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val tapScope = androidx.compose.runtime.rememberCoroutineScope() // Controls visibility of the big lock icon
    
    // Drag for Lock (0f to 1f)
    var lockDragProgress by remember { mutableFloatStateOf(0f) }
    
    var currentSpeedMultiplier by remember { mutableFloatStateOf(2.0f) } 
    var originalSpeed by remember { mutableFloatStateOf(1f) }
    
    // Y-Tracking for Zone Detection
    var activeZoneIndex by remember { mutableStateOf(1) } 

    // Safely reset speed/state if disposed
    DisposableEffect(Unit) {
        onDispose {
            if (isBoosting || isLocked) {
                onSpeedChange(originalSpeed)
            }
        }
    }
    
    // Auto-Hide Lock Controls
    LaunchedEffect(isLockControlsVisible) {
        if (isLockControlsVisible) {
            delay(3000)
            isLockControlsVisible = false
        }
    }

    // Reset Speed/State on Unlock
    LaunchedEffect(isLocked) {
         if (!isLocked) {
             isBoosting = false
             isRewinding = false
             onSpeedChange(originalSpeed)
         }
    }

    // Rewind Loop Logic
    LaunchedEffect(isRewinding, currentSpeedMultiplier, isLocked) {
        if (isRewinding || (isLocked && isRewinding)) { 
            if (!isLocked) originalSpeed = player.playbackParameters.speed
            if (player.isPlaying) player.pause()
            
            while (isActive) {
                val rewindStep = (currentSpeedMultiplier * 100).toLong() 
                val target = player.currentPosition - rewindStep
                player.seekTo(target.coerceAtLeast(0))
                delay(30) 
            }
        } else if (!isBoosting && !isRewinding && !isLocked) {
             if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
             } else {
                 onPlayPause()
             }
        }
    }

    // Forward Speed Logic
    LaunchedEffect(isBoosting, currentSpeedMultiplier, isLocked) {
        if (isBoosting || (isLocked && isBoosting)) {
            if (player.playbackParameters.speed != currentSpeedMultiplier) {
                onSpeedChange(currentSpeedMultiplier)
            }
        } else if (!isRewinding && !isLocked) {
             if (player.playbackParameters.speed != originalSpeed) {
                onSpeedChange(originalSpeed)
            }
        }
    }
    
    // Time-Based Lock Logic
    LaunchedEffect(isBoosting, isRewinding, isLocked) {
        if ((isBoosting || isRewinding) && !isLocked) {
            val startTime = System.currentTimeMillis()
            val lockDuration = 5000L // 5 seconds to lock
            
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                lockDragProgress = (elapsed / lockDuration.toFloat()).coerceIn(0f, 1f)
                
                if (lockDragProgress >= 1f) {
                    isLocked = true
                    // Vibrate or feedback here could be nice
                }
                delay(16) // ~60fps update
            }
        } else {
            lockDragProgress = 0f
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
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        
                        
                        // LOCKED STATE HANDLING
                        if (isLocked) {
                            down.consume()
                            isLockControlsVisible = !isLockControlsVisible
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
                                    
                                    val dragAmount = change.positionChange().getDistance()
                                    if (dragAmount > viewConfiguration.touchSlop) {
                                        // MOVED > SLOP -> DRAG
                                        isDrag = true
                                         throw kotlinx.coroutines.CancellationException("Drag detected") 
                                    }
                                    change.consume()
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
                                if (isLeftZone) {
                                    // Rewind 10s
                                    onSeek((player.currentPosition - 10000).coerceAtLeast(0))
                                    seekRippleState = SeekRippleData(isForward = false)
                                } else {
                                    // Forward 10s
                                    onSeek((player.currentPosition + 10000).coerceAtMost(player.duration))
                                    seekRippleState = SeekRippleData(isForward = true)
                                }
                                // Clear ripple after animation
                                tapScope.launch {
                                    delay(600)
                                    seekRippleState = null
                                }
                                lastTapTime = 0L 
                            } else {
                                // SINGLE TAP -> SCHEDULE PLAY/PAUSE
                                lastTapTime = currentTime
                                tapJob = tapScope.launch {
                                    delay(300) // Wait for potential second tap
                                    if (player.isPlaying) {
                                        onPlayPause()
                                        playPauseState = PlayPauseData(isPlaying = false)
                                    } else {
                                        onPlayPause()
                                        playPauseState = PlayPauseData(isPlaying = true)
                                    }
                                    onTap()
                                    delay(800)
                                    playPauseState = null
                                }
                            }
                            return@awaitEachGesture
                        }
                        
                        // --- HOLD / DRAG PHASE (Speed Zone) ---
                        // If we are here, it's either a TIMEOUT (Hold) or a DRAG start
                        
                        currentSpeedMultiplier = when(activeZoneIndex) {
                            0 -> 3.0f
                            1 -> 2.0f
                            else -> 1.5f
                        }
                        
                        originalSpeed = player.playbackParameters.speed
                        
                        if (isLeftZone) {
                            isRewinding = true
                        } else {
                             isBoosting = true
                        }
                        onGestureActive(true)
                        
                        var totalDragX = 0f
                        
                        // Hold Loop (Wait for release, update zones)
                        // Progress is handled by LaunchedEffect above
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { 
                                if (it.pressed) {
                                    val currentY = it.position.y
                                    activeZoneIndex = (currentY / zoneHeight).toInt().coerceIn(0, 2)
                                    
                                     currentSpeedMultiplier = when(activeZoneIndex) {
                                        0 -> 3.0f
                                        1 -> 2.0f
                                        else -> 1.5f
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
                        lockDragProgress = 0f
                    }
                }
        )
        
        // 3. VISUAL FEEDBACK LAYERS
        
        // Seek Ripple
        seekRippleState?.let { data ->
            SeekRippleOverlay(isForward = data.isForward, modifier = Modifier.fillMaxSize())
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

        // 4. LOCK OVERLAY (Button Based - Unlock Only)
        if (isLocked && isLockControlsVisible) {
             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                 Column(horizontalAlignment = Alignment.CenterHorizontally) {
                     androidx.compose.material3.IconButton(
                         onClick = { isLocked = false },
                         modifier = Modifier
                             .size(64.dp)
                             .background(Color.White, CircleShape)
                     ) {
                         Icon(
                             imageVector = Icons.Default.LockOpen,
                             contentDescription = "Unlock",
                             tint = Color.Black,
                             modifier = Modifier.size(32.dp)
                         )
                     }
                     Spacer(modifier = Modifier.size(8.dp))
                     Text("Tap to Unlock", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(4.dp))
                 }
             }
        }
        
        // 5. LOCK DRAG PROGRESS OVERLAY
        if (lockDragProgress > 0.05f && !isLocked) {
             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                 LockProgressOverlay(
                     progress = lockDragProgress,
                     modifier = Modifier.padding(top = 48.dp)
                 )
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
fun SeekRippleOverlay(isForward: Boolean, modifier: Modifier = Modifier) {
    val align = if (isForward) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isForward) RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp) else RoundedCornerShape(topEnd = 100.dp, bottomEnd = 100.dp)
    
    Box(modifier = modifier, contentAlignment = align) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight(0.6f)
                .width(100.dp)
                .background(Color.White.copy(alpha = 0.2f), shape)
        ) {
            Icon(
                imageVector = if (isForward) androidx.compose.material.icons.Icons.Filled.FastForward else androidx.compose.material.icons.Icons.Filled.FastRewind,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
            Text(text = "10s", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}


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
