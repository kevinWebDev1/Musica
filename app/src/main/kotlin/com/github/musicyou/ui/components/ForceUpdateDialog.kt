package com.github.musicyou.ui.components

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.utils.VersionConfig

/**
 * Non-dismissible update dialog that blocks app usage until user updates
 */
@Composable
fun ForceUpdateDialog(
    versionConfig: VersionConfig,
    onUpdateClick: () -> Unit
) {
    val colors = rememberNeumorphicColors()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = { /* Cannot dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(colors.background, RoundedCornerShape(28.dp))
                .border(1.dp, colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(28.dp))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Warning icon
                Icon(
                    imageVector = Icons.Outlined.Update,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(64.dp)
                )

                Text(
                    text = "Update Required",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = versionConfig.updateMessage,
                    fontSize = 14.sp,
                    color = colors.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Update Now Button (Only option)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Color(0xFF4CAF50).copy(alpha = 0.2f),
                            RoundedCornerShape(16.dp)
                        )
                        .border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onUpdateClick),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Update,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "Update Now",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                // Exit app option (small, subtle)
                TextButton(
                    onClick = {
                        (context as? Activity)?.finishAffinity()
                    }
                ) {
                    Text(
                        text = "Exit App",
                        color = colors.onBackground.copy(alpha = 0.3f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Optional/Recommended update dialog (dismissible)
 */
@Composable
fun OptionalUpdateDialog(
    versionConfig: VersionConfig,
    isRecommended: Boolean,
    onUpdateClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = rememberNeumorphicColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Update,
                contentDescription = null,
                tint = if (isRecommended) Color(0xFFFF9800) else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = if (isRecommended) "Update Recommended" else "Update Available",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = versionConfig.updateMessage,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onUpdateClick) {
                Text("Update Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}
