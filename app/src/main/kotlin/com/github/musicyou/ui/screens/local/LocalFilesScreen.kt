package com.github.musicyou.ui.screens.local

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.models.Song
import com.github.musicyou.utils.DeviceMediaManager
import com.github.musicyou.utils.asMediaItem
import com.github.musicyou.utils.forcePlayAtIndex
import com.github.musicyou.utils.isAtLeastAndroid13
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(
    pop: () -> Unit
) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    var mediaList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // All, Audio, Video

    val permissions = if (isAtLeastAndroid13) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            hasPermission = true
        }
    }

    LaunchedEffect(Unit) {
        // Check initial permission status
        val allGranted = permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, it
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            hasPermission = true
        } else {
            launcher.launch(permissions)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            mediaList = withContext(Dispatchers.IO) {
                DeviceMediaManager.getDeviceMedia(context)
            }
        }
    }

    // Filter logic
    val filteredList = remember(mediaList, searchQuery, selectedFilter) {
        mediaList.filter { song ->
            val matchesSearch = song.title.contains(searchQuery, ignoreCase = true)
            val isVideo = song.artistsText == "Video"
            val matchesFilter = when (selectedFilter) {
                "Audio" -> !isVideo
                "Video" -> isVideo
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Device Files") },
                    navigationIcon = {
                        IconButton(onClick = pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                )

                if (hasPermission) {
                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("Search files...") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    )

                    // Filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All", "Audio", "Video").forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter) },
                                leadingIcon = if (selectedFilter == filter) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (!hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Permission required to access local files")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(permissions) }) {
                        Text("Grant Permission")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (filteredList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No matching files found.")
                        }
                    }
                } else {
                    itemsIndexed(filteredList) { index, song ->
                        ListItem(
                            headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${song.artistsText} • ${song.durationText}", maxLines = 1) },
                            leadingContent = {
                                Text((index + 1).toString())
                            },
                            modifier = Modifier.clickable {
                                binder?.stopRadio()
                                binder?.player?.forcePlayAtIndex(
                                    filteredList.map { it.asMediaItem }, // Play only filtered list
                                    index
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
