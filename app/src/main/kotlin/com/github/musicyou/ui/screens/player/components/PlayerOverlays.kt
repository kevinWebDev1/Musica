package com.github.musicyou.ui.screens.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun VolumeBrightnessOverlay(
    value: Float,
    isVolume: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = if (isVolume) Icons.Default.VolumeUp else Icons.Default.BrightnessMedium
    val align = if (isVolume) Alignment.CenterEnd else Alignment.CenterStart
    val padding = if (isVolume) PaddingValues(end = 32.dp) else PaddingValues(start = 32.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = align
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(48.dp)
                .height(200.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(vertical = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .weight(1f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(value.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(Color.White)
                )
                if (isVolume && value > 1f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((value - 1f).coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(Color(0xFFFF9800)) // Orange for software boost
                    )
                }
            }
        }
    }
}

@Composable
fun DoubleTapSeekOverlay(
    isForward: Boolean,
    modifier: Modifier = Modifier
) {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.8f) }
    
    LaunchedEffect(Unit) {
        // Fade in and scale up quickly, then fade out
        alphaAnim.animateTo(1f, animationSpec = tween(150, easing = LinearEasing))
        scaleAnim.animateTo(1.2f, animationSpec = tween(400, easing = LinearEasing))
        alphaAnim.animateTo(0f, animationSpec = tween(200, easing = LinearEasing))
    }

    val align = if (isForward) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isForward) RoundedCornerShape(topStart = 150.dp, bottomStart = 150.dp) else RoundedCornerShape(topEnd = 150.dp, bottomEnd = 150.dp)
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = align
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .width(140.dp)
                .alpha(alphaAnim.value)
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
                .background(Color.White.copy(alpha = 0.15f), shape)
        ) {
            Icon(
                imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "10s", 
                color = Color.White, 
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
