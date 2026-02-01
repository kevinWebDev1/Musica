package com.github.musicyou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.ui.styling.neumorphicSurface
import com.github.musicyou.ui.styling.neumorphicPressed

@Composable
fun ReviewReminderDialog(
    onReview: () -> Unit,
    onSkip: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var rating by remember { mutableIntStateOf(0) }
    val colors = rememberNeumorphicColors()

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(colors.background, RoundedCornerShape(28.dp))
                .border(1.dp, colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (currentStep) {
                1 -> {
                    // Step 1: Make user feel valued and ask if they're enjoying
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Emoji or icon to make it friendly
                        Text(
                            text = "💖",
                            fontSize = 48.sp,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "You are our precious\nMusica Member",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onBackground,
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp
                        )

                        Text(
                            text = "Are you enjoying the app?",
                            fontSize = 16.sp,
                            color = colors.onBackground.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Yes / Not Really buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Not Really Button - appears after 5 seconds
                            var showNotReally by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(5000)
                                showNotReally = true
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showNotReally,
                                    enter = androidx.compose.animation.fadeIn()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(colors.background, RoundedCornerShape(14.dp))
                                            .border(1.dp, colors.onBackground.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                            .clickable(onClick = onSkip),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Not Really",
                                            color = colors.onBackground.copy(alpha = 0.6f),
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }

                            // Yes Button - proceeds to step 2
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(
                                        Color(0xFF4CAF50).copy(alpha = 0.15f),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .clickable { currentStep = 2 },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Yes! 😊",
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }

                2 -> {
                    // Step 2: Ask for rating
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "That's wonderful! 🎵",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onBackground,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Please take out a moment from your precious time to give it a rating",
                            fontSize = 14.sp,
                            color = colors.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        // Star Rating
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            (1..5).forEach { index ->
                                val isSelected = index <= rating
                                Icon(
                                    imageVector = if (isSelected) Icons.Filled.Star else Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = if (isSelected) Color(0xFFFFD700) else colors.onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable {
                                            rating = index
                                            onReview() // Redirect on star click
                                        }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Rate Now Button - Primary, prominent on left
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .background(
                                        Color(0xFFFFD700).copy(alpha = 0.2f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(1.dp, Color(0xFFFFD700).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable(onClick = onReview),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Rate Now ⭐",
                                    color = Color(0xFFFFA000),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            // Maybe Later Button - Almost invisible on right
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clickable(onClick = onSkip),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Maybe Later",
                                    color = colors.onBackground.copy(alpha = 0.25f),
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
