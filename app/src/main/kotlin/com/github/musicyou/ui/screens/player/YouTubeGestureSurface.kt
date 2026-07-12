package com.github.musicyou.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.musicyou.ui.screens.player.components.DoubleTapSeekOverlay
import com.github.musicyou.ui.screens.player.components.VolumeBrightnessOverlay
import com.github.musicyou.utils.doubleTapSeekDurationKey
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.scrubSeekIntensityKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

@Composable
fun YouTubeGestureSurface(
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    durationMs: Long,
    currentPositionMs: Long,
    isLocked: Boolean = false,
    onLockedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    // Gesture State
    var isLockControlsVisible by remember { mutableStateOf(false) }
    
    // Feedback State
    var seekRippleState by remember { mutableStateOf<SeekRippleData?>(null) }
    var scrubToastState by remember { mutableStateOf<String?>(null) } 
    
    // Preferences
    val doubleTapSeekDuration by rememberPreference(doubleTapSeekDurationKey, 10)
    val scrubSeekIntensity by rememberPreference(scrubSeekIntensityKey, 1.0f)
    
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
    
    var currentVolume by remember { mutableStateOf<Float?>(null) }
    var currentBrightness by remember { mutableStateOf<Float?>(null) }
    
    // Tap Timing State
    var lastTapTime by remember { mutableStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    val tapScope = rememberCoroutineScope() 
    
    // Reset State on Unlock
    LaunchedEffect(isLocked) {
         if (!isLocked) {
             isLockControlsVisible = false
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. YouTube Video Content Layer
        content()
        
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
                        
                        val isLeftZone = startX < screenWidth / 2
                        
                        down.consume()
                        
                        var isTap = false
                        var isDrag = false
                        var dragType = 0 // 1: Vertical, 2: Horizontal
                        val longPressTimeout = 250L
                        
                        try {
                            withTimeout(longPressTimeout) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    
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
                        
                        // --- HANDLE TAP ---
                        if (isTap) {
                            val currentTime = System.currentTimeMillis()
                            val isDoubleTap = (currentTime - lastTapTime) < 300
                            
                            if (isDoubleTap) {
                                // DOUBLE TAP -> SEEK
                                tapJob?.cancel()
                                val seekDurationMs = doubleTapSeekDuration * 1000L
                                if (isLeftZone) {
                                    onSeek((currentPositionMs - seekDurationMs).coerceAtLeast(0))
                                    seekRippleState = SeekRippleData(isForward = false)
                                } else {
                                    onSeek((currentPositionMs + seekDurationMs).coerceAtMost(durationMs.coerceAtLeast(0L)))
                                    seekRippleState = SeekRippleData(isForward = true)
                                }
                                tapScope.launch {
                                    delay(600)
                                    seekRippleState = null
                                }
                                lastTapTime = 0L 
                            } else {
                                // SINGLE TAP -> TOGGLE CONTROLS
                                lastTapTime = currentTime
                                tapJob = tapScope.launch {
                                    delay(300)
                                    onTap()
                                }
                            }
                            return@awaitEachGesture
                        }
                        
                        // --- DRAG PHASE ---
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
                                startVolume = if (maxVol > 0) currentVol / maxVol else 0f
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
                                        val newVolume = (startVolume + changePercent).coerceIn(0f, 1f)
                                        currentVolume = newVolume
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (newVolume * maxVol).roundToInt(), 0)
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
                            val videoDuration = durationMs.coerceAtLeast(1L)
                            
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val deltaX = change.positionChange().x
                                    totalDragX += deltaX
                                    
                                    val seekRangeMs = if (videoDuration > 600000L) {
                                        videoDuration / 10f
                                    } else {
                                        kotlin.math.min(120000f, videoDuration.toFloat())
                                    }
                                    val seekMsPerPixel = (seekRangeMs / maxDragDistance) * scrubSeekIntensity
                                    
                                    val seekAmount = (totalDragX * seekMsPerPixel).toLong()
                                    val newPosition = (currentPositionMs + seekAmount).coerceIn(0, videoDuration)
                                    
                                    onSeek(newPosition)
                                    
                                    // Feedback using toast
                                    val totalSeconds = newPosition / 1000
                                    val minutes = totalSeconds / 60
                                    val seconds = totalSeconds % 60
                                    val timeString = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                                    
                                    val diff = newPosition - currentPositionMs
                                    val sign = if (diff >= 0) "+" else "-"
                                    val diffSeconds = kotlin.math.abs(diff) / 1000
                                    val diffMins = diffSeconds / 60
                                    val diffSecs = diffSeconds % 60
                                    val diffString = "${sign}${diffMins.toString().padStart(2, '0')}:${diffSecs.toString().padStart(2, '0')}"
                                    
                                    scrubToastState = "$timeString  [$diffString]"
                                    
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            
                            tapScope.launch {
                                delay(800)
                                if (scrubToastState?.contains("[") == true) {
                                    scrubToastState = null
                                }
                            }
                            
                        } else {
                            // Hold / Unhandled - Consume remaining touches
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                }
        )
        
        // 3. VISUAL FEEDBACK LAYERS
        
        seekRippleState?.let { data ->
            DoubleTapSeekOverlay(isForward = data.isForward)
        }
        
        currentVolume?.let { vol ->
            VolumeBrightnessOverlay(value = vol, isVolume = true)
        }
        
        currentBrightness?.let { bright ->
            VolumeBrightnessOverlay(value = bright, isVolume = false)
        }
        
        scrubToastState?.let { text ->
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
                        imageVector = Icons.Default.LockOpen,
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
    }
}
