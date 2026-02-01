package com.github.musicyou.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.musicyou.R
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.ui.components.DeleteAccountDialog
import com.github.musicyou.ui.screens.settings.SwitchSettingEntry
import com.github.musicyou.ui.screens.settings.SettingsEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@ExperimentalFoundationApi
@Composable
fun AdvancedSettingsScreen(
    pop: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    
    var privacySettings by remember { mutableStateOf(ProfileManager.PrivacySettings()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ProfileManager.getPrivacySettings().onSuccess {
            privacySettings = it
            isLoading = false
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(id = R.string.advanced)) },
                navigationIcon = {
                    IconButton(onClick = pop) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (isLoading) {
             // Loading state (could be improved)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Text(
                    text = stringResource(id = R.string.privacy_settings),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )

                SwitchSettingEntry(
                    title = stringResource(id = R.string.share_liked_songs),
                    text = if (privacySettings.shareLikedSongs) "Visible to friends" else "Hidden",
                    icon = Icons.Rounded.Favorite,
                    isChecked = privacySettings.shareLikedSongs,
                    onCheckedChange = { isChecked ->
                        val newSettings = privacySettings.copy(shareLikedSongs = isChecked)
                        privacySettings = newSettings
                        scope.launch { ProfileManager.updatePrivacySettings(newSettings) }
                    }
                )

                SwitchSettingEntry(
                    title = stringResource(id = R.string.share_public_playlists),
                    text = if (privacySettings.sharePlaylists) "Visible to friends" else "Hidden",
                    icon = Icons.Rounded.QueueMusic,
                    isChecked = privacySettings.sharePlaylists,
                    onCheckedChange = { isChecked ->
                        val newSettings = privacySettings.copy(sharePlaylists = isChecked)
                        privacySettings = newSettings
                        scope.launch { ProfileManager.updatePrivacySettings(newSettings) }
                    }
                )

                SwitchSettingEntry(
                    title = stringResource(id = R.string.show_activity_status),
                    text = if (privacySettings.showActivity) "Sharing listening activity" else "Hidden",
                    icon = if (privacySettings.showActivity) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    isChecked = privacySettings.showActivity,
                    onCheckedChange = { isChecked ->
                        val newSettings = privacySettings.copy(showActivity = isChecked)
                        privacySettings = newSettings
                        scope.launch { ProfileManager.updatePrivacySettings(newSettings) }
                    }
                )
                
                Divider()
                
                SettingsEntry(
                    title = stringResource(id = R.string.delete_account),
                    text = "Permanently delete your account and data",
                    icon = Icons.Outlined.DeleteForever,
                    onClick = { showDeleteDialog = true },
                    // Tinting handling might need custom component adjustment or just rely on text/icon default
                )
            }
        }
    }
    
    if (showDeleteDialog) {
        DeleteAccountDialog(
            onDismiss = { showDeleteDialog = false },
            onDeleted = {
                showDeleteDialog = false
                // Handle logout/pop via callback passed or assumed global auth state listener will kick in
                // For now just pop, assuming ProfileManager handles auth signout
                pop() 
            },
            onRequireLogin = {
                showDeleteDialog = false
                // Should prompt re-login
            }
        )
    }
}
