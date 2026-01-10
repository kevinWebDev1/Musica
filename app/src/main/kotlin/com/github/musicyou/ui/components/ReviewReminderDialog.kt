package com.github.musicyou.ui.components

import androidx.compose.foundation.background
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
                .neumorphicSurface(cornerRadius = 28.dp)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enjoying Musica?",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Your feedback helps us make the app even better for everyone!",
                    fontSize = 14.sp,
                    color = colors.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                // Star Rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    (1..5).forEach { index ->
                        val isSelected = index <= rating
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFFFFD700) else colors.onBackground.copy(alpha = 0.3f),
                            modifier = Modifier
                                .size(40.dp)
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
                    // Skip Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .neumorphicSurface(cornerRadius = 12.dp)
                            .clickable(onClick = onSkip),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Skip",
                            color = colors.onBackground.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Review Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .neumorphicSurface(cornerRadius = 12.dp)
                            .clickable(onClick = onReview),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Review Now",
                            color = colors.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
