package com.github.musicyou.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.musicyou.R

/**
 * Dialog to prompt user for their display name.
 * Used on first app launch and for sync identification.
 */
@Composable
fun NameEntryDialog(
    onNameEntered: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss without entering name */ },
        title = { 
            Text(
                text = "Welcome!",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Please enter your name to continue. This will be shown to others when you sync music.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        isError = false
                    },
                    label = { Text("Your Name") },
                    placeholder = { Text("Enter your name...") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Name is required") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isBlank()) {
                        isError = true
                    } else {
                        onNameEntered(trimmedName)
                    }
                }
            ) {
                Text("Continue")
            }
        }
    )
}
