package com.github.musicyou.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import coil3.compose.AsyncImage
import com.github.musicyou.R
import com.github.musicyou.utils.preferences
import com.github.musicyou.utils.profileImageUrlKey
import com.github.musicyou.utils.profileImageLastUpdatedKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(
    @StringRes title: Int,
    titleContent: @Composable (() -> Unit)? = null,
    snackbarHost: @Composable (() -> Unit) = {},
    floatingActionButton: @Composable (() -> Unit) = {},
    openSearch: () -> Unit,
    openProfile: () -> Unit = {},
    profileContent: @Composable (() -> Unit)? = null,
    openSettings: () -> Unit,
    content: @Composable (() -> Unit)
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    
    // Force recomposition when preferences change (basic way)
    // In a real app we'd observe the preference flow

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.app_icon),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(text = stringResource(id = title))
                        }
                    }
                },
                actions = {
                    TooltipIconButton(
                        description = R.string.search,
                        onClick = openSearch,
                        icon = Icons.Outlined.Search,
                        inTopBar = true
                    )

                    if (profileContent != null) {
                        profileContent()
                    } else {
                        val context = LocalContext.current
                        
                        // Observe both URL and timestamp for cache busting
                        val photoUrl = com.github.musicyou.utils.observePreference(profileImageUrlKey, "").value
                        val lastUpdated = com.github.musicyou.utils.observePreference(profileImageLastUpdatedKey, 0L).value
                        
                        // Construct cache-busted URL
                        val finalPhotoUrl = remember(photoUrl, lastUpdated) {
                            if (photoUrl.isNotBlank() && lastUpdated > 0) {
                                "$photoUrl?ts=$lastUpdated"
                            } else if (photoUrl.isNotBlank()) {
                                photoUrl
                            } else {
                                null
                            }
                        }
                        
                        if (finalPhotoUrl != null) {
                            AsyncImage(
                                model = finalPhotoUrl,
                                contentDescription = stringResource(id = R.string.profile),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { openProfile() },
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            TooltipIconButton(
                                description = R.string.profile,
                                onClick = openProfile,
                                icon = Icons.Outlined.Person,
                                inTopBar = true
                            )
                        }
                    }

                    TooltipIconButton(
                        description = R.string.settings,
                        onClick = openSettings,
                        icon = Icons.Outlined.Settings,
                        inTopBar = true
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = WindowInsets()
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = paddingValues),
            content = content
        )
    }
}
