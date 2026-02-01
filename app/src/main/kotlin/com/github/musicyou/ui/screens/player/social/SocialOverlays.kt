package com.github.musicyou.ui.screens.player.social

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.sync.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ReactionOverlay(
    event: ReactionEvent?,
    modifier: Modifier = Modifier
) {
    var reactions by remember { mutableStateOf(listOf<ReactionInstance>()) }
    val scope = rememberCoroutineScope()

    // Observe full event object (trigger on new instance/timestamp)
    LaunchedEffect(event) {
        if (event != null) {
            val burstCount = 12
            val newReactions = List(burstCount) {
                ReactionInstance(
                    emoji = event.emoji,
                    delay = (it * 80).toLong(),
                    startX = kotlin.random.Random.nextInt(15, 85).toFloat() / 100f,
                    drift = kotlin.random.Random.nextInt(-20, 20).toFloat() / 100f,
                    speed = kotlin.random.Random.nextDouble(0.6, 1.1).toFloat()
                )
            }
            reactions = reactions + newReactions
            
            // Clean up this specific burst after its animation duration
            scope.launch {
                delay(5000)
                reactions = reactions.filter { r -> newReactions.none { it.id == r.id } }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        reactions.forEach { reaction ->
            key(reaction.id) {
                FloatingEmoji(reaction)
            }
        }
    }
}

data class ReactionInstance(
    val emoji: String,
    val delay: Long,
    val startX: Float,
    val drift: Float,
    val speed: Float,
    val id: String = UUID.randomUUID().toString()
)

@Composable
fun FloatingEmoji(instance: ReactionInstance) {
    val duration = (2500 / instance.speed).toInt()
    val animatedProgress = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        delay(instance.delay)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(duration, easing = LinearOutSlowInEasing)
        )
    }

    val progress = animatedProgress.value
    if (progress > 0f && progress < 1f) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val width = maxWidth
            val height = maxHeight
            
            val xPos = width * (instance.startX + (instance.drift * progress))
            val yPos = height * (0.85f - (0.8f * progress))
            
            Text(
                text = instance.emoji,
                fontSize = (28 + (progress * 15)).sp,
                modifier = Modifier
                    .offset(x = xPos, y = yPos)
                    .alpha(if (progress < 0.2f) progress * 5f else 1f - progress)
                    .scale(0.7f + (progress * 0.3f))
            )
        }
    }
}

@Composable
fun FlashMessageOverlay(
    event: FlashMessageEvent?,
    modifier: Modifier = Modifier
) {
    var currentMessage by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        if (event != null) {
            currentMessage = event.message to event.senderName
            visible = true
            delay(3500)
            visible = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize().padding(bottom = 180.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + scaleIn(tween(400, easing = OvershootEasing)),
            exit = fadeOut(tween(400)) + scaleOut(tween(400))
        ) {
            currentMessage?.let { (msg, sender) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(28.dp)
                        )
                        .padding(horizontal = 28.dp, vertical = 14.dp)
                ) {
                    if (sender != null) {
                        Text(
                            text = sender.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
                    )
                }
            }
        }
    }
}

@Composable
fun KineticTouchOverlay(
    event: KineticTouchEvent?,
    modifier: Modifier = Modifier
) {
    var ripples by remember { mutableStateOf(listOf<RippleInstance>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(event) {
        if (event != null) {
            val newRipple = RippleInstance(event.x, event.y)
            ripples = ripples + newRipple
            scope.launch {
                delay(1200)
                ripples = ripples.filter { it.id != newRipple.id }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ripples.forEach { ripple ->
            key(ripple.id) {
                TouchRipple(x = ripple.x, y = ripple.y)
            }
        }
    }
}

data class RippleInstance(
    val x: Float,
    val y: Float,
    val id: String = UUID.randomUUID().toString()
)

@Composable
fun TouchRipple(x: Float, y: Float) {
    val animatedProgress = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1000, easing = EaseOutExpo)
        )
    }

    val progress = animatedProgress.value
    if (progress < 1f) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val size = 120.dp
            Box(
                modifier = Modifier
                    .offset(
                        x = maxWidth * x - size / 2,
                        y = maxHeight * y - size / 2
                    )
                    .size(size)
                    .scale(0.5f + progress * 1.5f)
                    .alpha(0.4f * (1f - progress))
                    .background(Color.White.copy(alpha = 0.8f), shape = CircleShape)
            )
        }
    }
}

val OvershootEasing = Easing { fraction ->
    val t = fraction - 1.0f
    t * t * ((2.0f + 1.0f) * t + 2.0f) + 1.0f
}
