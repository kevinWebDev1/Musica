package com.github.musicyou.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.musicyou.auth.ProfileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Softer, professional "Danger" colors
private val SoftRed = Color(0xFFB71C1C)
private val MistRed = Color(0xFFFFF1F1)

@Composable
fun DeleteAccountDialog(
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onRequireLogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableStateOf(DeleteStep.WARNING) }
    var confirmText by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var deletionError by remember { mutableStateOf<String?>(null) }
    var deletionProgress by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!isDeleting) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // 1. Top Bar with Back Button
                FlowTopBar(
                    step = currentStep,
                    onBack = { currentStep = DeleteStep.WARNING },
                    onClose = onDismiss,
                    showClose = !isDeleting
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 2. Main Content
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            if (targetState.stepIndex > initialState.stepIndex) {
                                slideInHorizontally { it } + fadeIn() togetherWith
                                        slideOutHorizontally { -it } + fadeOut()
                            } else {
                                slideInHorizontally { -it } + fadeIn() togetherWith
                                        slideOutHorizontally { it } + fadeOut()
                            }
                        },
                        label = "StepTransition"
                    ) { step ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            when (step) {
                                DeleteStep.WARNING -> WarningStep(
                                    onCancel = onDismiss,
                                    onContinue = { currentStep = DeleteStep.CONFIRM_TYPE }
                                )

                                DeleteStep.CONFIRM_TYPE -> ConfirmStep(
                                    confirmText = confirmText,
                                    error = deletionError,
                                    onTextChange = { confirmText = it },
                                    onConfirm = {
                                        scope.launch {
                                            try {
                                                isDeleting = true
                                                currentStep = DeleteStep.PROGRESS
                                                handleDeletion(
                                                    context,
                                                    onUpdate = { deletionProgress = it },
                                                    onSuccess = { 
                                                        currentStep = DeleteStep.COMPLETE
                                                        // Wait for animation to finish before notifying parent
                                                        scope.launch {
                                                             delay(2000)
                                                             onDeleted()
                                                        }
                                                    },
                                                    onFailure = { errorMsg, requiresReauth ->
                                                        if (requiresReauth) {
                                                            currentStep = DeleteStep.REAUTH_REQUIRED
                                                            isDeleting = false
                                                        } else {
                                                            deletionError = errorMsg
                                                            currentStep = DeleteStep.CONFIRM_TYPE
                                                            isDeleting = false
                                                        }
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                deletionError = e.message ?: "Unexpected error"
                                                currentStep = DeleteStep.CONFIRM_TYPE
                                                isDeleting = false
                                            }
                                        }
                                    }
                                )

                                DeleteStep.PROGRESS -> ProgressStep(deletionProgress)

                                DeleteStep.COMPLETE -> CompleteStep()
                                
                                DeleteStep.REAUTH_REQUIRED -> ReauthStep(onLogout = onRequireLogin)
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class DeleteStep(val title: String, val stepIndex: Int) {
    WARNING("Delete Account", 0),
    CONFIRM_TYPE("Final Step", 1),
    PROGRESS("Processing", 2),
    REAUTH_REQUIRED("Action Required", 3),
    COMPLETE("Account Deleted", 4)
}

// ... existing FlowTopBar, WarningStep ...

@Composable
private fun ReauthStep(onLogout: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(MistRed, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = SoftRed, modifier = Modifier.size(32.dp))
    }

    Text(
        "Security Check Required",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Text(
        "For your security, Google requires you to have signed in recently to delete your account.\n\nYour profile data has been cleared, but to complete the account removal, please log in again.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        onClick = onLogout,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Log Out & Try Again")
    }
}

// ... existing ConfirmStep, ProgressStep, CompleteStep ...


@Composable
private fun FlowTopBar(
    step: DeleteStep,
    onBack: () -> Unit,
    onClose: () -> Unit,
    showClose: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Only show back on the confirmation step
        if (step == DeleteStep.CONFIRM_TYPE) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }

        Text(
            text = step.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (showClose && step != DeleteStep.COMPLETE) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun WarningStep(onCancel: () -> Unit, onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(MistRed, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = SoftRed, modifier = Modifier.size(32.dp))
    }

    Text(
        "Are you sure?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    Text(
        "Deletions are permanent. You will lose all your music data immediately.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cancel")
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun ConfirmStep(
    confirmText: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit
) {
    Text(
        "To confirm, please type 'DELETE' below.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )

    OutlinedTextField(
        value = confirmText,
        onValueChange = { onTextChange(it.uppercase()) },
        modifier = Modifier.fillMaxWidth(),
        isError = error != null,
        placeholder = { Text("DELETE", color = Color.LightGray) },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SoftRed,
            cursorColor = SoftRed
        ),
        singleLine = true
    )

    Button(
        onClick = onConfirm,
        enabled = confirmText == "DELETE",
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SoftRed,
            disabledContainerColor = SoftRed.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Delete Permanently", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressStep(progressText: String) {
    CircularProgressIndicator(color = SoftRed, strokeWidth = 3.dp)
    Text(progressText, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CompleteStep() {
    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(64.dp))
    Text("Success", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Your account has been wiped.", textAlign = TextAlign.Center)
}

private suspend fun handleDeletion(
    context: android.content.Context,
    onUpdate: (String) -> Unit,
    onSuccess: () -> Unit,
    onFailure: (String, Boolean) -> Unit
) {
    onUpdate("Removing account data...")
    delay(1000)
    val result = ProfileManager.deleteAccount(context)
    if (result.isSuccess) {
        onSuccess()
    } else {
        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown Error"
        // Check for the specific "Security Check" message we set in ProfileManager
        val isReauthError = errorMsg.contains("Security Check") || errorMsg.contains("recent authentication")
        onFailure(errorMsg, isReauthError)
    }
}