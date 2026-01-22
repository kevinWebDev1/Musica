package com.github.musicyou.ui.screens.settings

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.github.musicyou.auth.ProfileManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.R
import com.github.musicyou.enums.SettingsSection
import com.github.musicyou.ui.components.ValueSelectorDialog
import com.github.musicyou.ui.styling.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@ExperimentalFoundationApi
@ExperimentalAnimationApi
@Composable
fun SettingsScreen(
    pop: () -> Unit,
    onGoToSettingsPage: (Int) -> Unit
) {
    val playerPadding = LocalPlayerPadding.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(text = stringResource(id = R.string.settings))
                },
                navigationIcon = {
                    IconButton(onClick = pop) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(bottom = playerPadding)
        ) {
            SettingsSection.entries.forEachIndexed { index, section ->
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(id = section.resourceId))
                    },
                    modifier = Modifier.clickable { onGoToSettingsPage(index) },
                    leadingContent = {
                        Icon(
                            imageVector = section.icon,
                            contentDescription = stringResource(id = section.resourceId)
                        )
                    }
                )
            }
            
            // Logout Option
            ListItem(
                headlineContent = {
                    Text(text = "Logout")
                },
                modifier = Modifier.clickable {
                    // Sign out from Firebase
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    
                    // Clear local preferences
                    context.getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                    
                    // Navigate back to login/onboarding
                    pop()
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                        contentDescription = "Logout"
                    )
                }
            )
            
            // Delete Account Option
            ListItem(
                headlineContent = {
                    Text(text = "Delete Account", color = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable { showDeleteDialog = true },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = "Delete Account",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
    
    if (showDeleteDialog) {
        var confirmText by remember { mutableStateOf("") }
        val isConfirmed = confirmText == "DELETE"
        
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Account") },
            text = {
                Column {
                    Text("This action is irreversible. All your data will be lost.")
                    Text("Type 'DELETE' to confirm.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        placeholder = { Text("DELETE") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            errorContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ProfileManager.deleteAccount(context).onSuccess {
                                // App will likely crash or need restart, ideally navigate to login
                                // But for now, we just let it clear and hopefully Main handles auth state change
                            }
                            showDeleteDialog = false
                        }
                    },
                    enabled = isConfirmed,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
inline fun <reified T : Enum<T>> EnumValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    crossinline onValueSelected: (T) -> Unit,
    icon: ImageVector,
    isEnabled: Boolean = true,
    crossinline valueText: (T) -> String = Enum<T>::name,
    noinline trailingContent: @Composable (() -> Unit)? = null
) {
    ValueSelectorSettingsEntry(
        title = title,
        selectedValue = selectedValue,
        values = enumValues<T>().toList(),
        onValueSelected = onValueSelected,
        icon = icon,
        isEnabled = isEnabled,
        valueText = valueText,
        trailingContent = trailingContent,
    )
}

@Composable
inline fun <T> ValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    values: List<T>,
    crossinline onValueSelected: (T) -> Unit,
    icon: ImageVector,
    isEnabled: Boolean = true,
    crossinline valueText: (T) -> String = { it.toString() },
    noinline trailingContent: @Composable (() -> Unit)? = null
) {
    var isShowingDialog by remember { mutableStateOf(false) }

    if (isShowingDialog) {
        ValueSelectorDialog(
            onDismiss = { isShowingDialog = false },
            title = title,
            selectedValue = selectedValue,
            values = values,
            onValueSelected = onValueSelected,
            valueText = valueText
        )
    }

    SettingsEntry(
        title = title,
        text = valueText(selectedValue),
        icon = icon,
        onClick = { isShowingDialog = true },
        isEnabled = isEnabled,
        trailingContent = trailingContent
    )
}

@Composable
fun SwitchSettingEntry(
    title: String,
    text: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isEnabled: Boolean = true
) {
    SettingsEntry(
        title = title,
        text = text,
        icon = icon,
        onClick = { onCheckedChange(!isChecked) },
        isEnabled = isEnabled
    ) {
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            enabled = isEnabled
        )
    }
}

@Composable
fun SettingsEntry(
    title: String,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(text = title)
        },
        modifier = Modifier
            .clickable(enabled = isEnabled, onClick = onClick)
            .alpha(if (isEnabled) 1F else Dimensions.lowOpacity),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title
            )
        },
        supportingContent = {
            Text(text = text)
        },
        trailingContent = { trailingContent?.invoke() }
    )
}

@Composable
fun SettingsInformation(
    text: String,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun SettingsProgress(text: String, progress: Float) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.width(240.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
        )
    }
}