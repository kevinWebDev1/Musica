package com.github.musicyou.ui.screens.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.musicyou.utils.isAtLeastAndroid13
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.R
import com.github.musicyou.models.Song
import com.github.musicyou.ui.components.HomeScaffold
import com.github.musicyou.ui.components.TooltipIconButton
import com.github.musicyou.ui.items.LocalSongItem
import com.github.musicyou.viewmodels.VideoFolder
import com.github.musicyou.viewmodels.VideosViewModel
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.innertube.Innertube
import com.github.musicyou.utils.asVideoMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.musicyou.ui.items.MediaSongItem
import com.github.musicyou.utils.forcePlay
import com.github.innertube.requests.searchPage
import com.github.innertube.requests.song
import com.github.innertube.utils.from
import com.github.innertube.requests.searchSuggestions
import androidx.media3.common.MediaItem
import kotlinx.coroutines.async
import com.github.musicyou.utils.asMediaItem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

enum class LocalMediaType {
    ALL, FOLDERS, MUSIC, YOUTUBE, MOVIES
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeVideos(
    openSearch: () -> Unit,
    openProfile: () -> Unit,
    openSettings: () -> Unit,
    onVideoClick: (Song) -> Unit
) {
    val playerPadding = LocalPlayerPadding.current
    val viewModel: VideosViewModel = viewModel()
    
    val filteredAllVideos by viewModel.filteredAllVideos.collectAsState()
    val filteredVideoFolders by viewModel.filteredVideoFolders.collectAsState()
    val filteredAllMusic by viewModel.filteredAllMusic.collectAsState()
    val filteredCombinedMedia by viewModel.filteredCombinedMedia.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var selectedFolder by remember { mutableStateOf<VideoFolder?>(null) }
    val selectedMediaTypeIndex by viewModel.selectedMediaTypeIndex.collectAsState()
    val selectedMediaType = LocalMediaType.entries[selectedMediaTypeIndex]
    val setSelectedMediaType: (LocalMediaType) -> Unit = { viewModel.selectedMediaTypeIndex.value = it.ordinal }
    
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    val permissions = if (isAtLeastAndroid13) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var hasPermission by remember { 
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(
                    context, it
                ) == PackageManager.PERMISSION_GRANTED
            }
        ) 
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            hasPermission = true
            viewModel.loadMedia()
        }
    }

    LaunchedEffect(Unit) {
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(
                context, it
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) hasPermission = true
    }

    BackHandler(enabled = selectedFolder != null) {
        selectedFolder = null
    }

    HomeScaffold(
        title = R.string.videos,
        openSearch = openSearch,
        openProfile = openProfile,
        openSettings = openSettings,
        extraActions = {
            if (hasPermission) {
                TooltipIconButton(
                    description = R.string.refresh,
                    onClick = { viewModel.loadMedia() },
                    icon = Icons.Outlined.Refresh,
                    inTopBar = true
                )
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding())) {
            // Header Content: Search Bar and Pills
            // Only show if not inside a folder
            AnimatedVisibility(
                visible = selectedFolder == null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    // Search Bar — hidden when YouTube or MovieBox tab is active (they have their own)
                    AnimatedVisibility(
                        visible = selectedMediaType != LocalMediaType.YOUTUBE && selectedMediaType != LocalMediaType.MOVIES,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("Search local media...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        viewModel.searchQuery.value = ""
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            shape = CircleShape,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        )
                    }

                    // Pill filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterPill(
                            text = "All",
                            selected = selectedMediaType == LocalMediaType.ALL,
                            onClick = { setSelectedMediaType(LocalMediaType.ALL) }
                        )
                        FilterPill(
                            text = "Folders",
                            selected = selectedMediaType == LocalMediaType.FOLDERS,
                            onClick = { setSelectedMediaType(LocalMediaType.FOLDERS) }
                        )
                        FilterPill(
                            text = "Music",
                            selected = selectedMediaType == LocalMediaType.MUSIC,
                            onClick = { setSelectedMediaType(LocalMediaType.MUSIC) }
                        )
                        FilterPill(
                            text = "YouTube",
                            selected = selectedMediaType == LocalMediaType.YOUTUBE,
                            onClick = { setSelectedMediaType(LocalMediaType.YOUTUBE) }
                        )
                        FilterPill(
                            text = "Movies & Shows",
                            selected = selectedMediaType == LocalMediaType.MOVIES,
                            onClick = { setSelectedMediaType(LocalMediaType.MOVIES) }
                        )
                    }
                }
            }
            
            // Content Area with Crossfade
            Crossfade(
                targetState = selectedFolder != null,
                label = "FolderCrossfade"
            ) { inFolder ->
                if (inFolder) {
                    val currentFolder = selectedFolder!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolder = null }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = currentFolder.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        val filteredVideosInFolder = if (searchQuery.isBlank()) {
                            currentFolder.videos
                        } else {
                            currentFolder.videos.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        }
                        
                        if (filteredVideosInFolder.isEmpty()) {
                            EmptyMediaState(
                                icon = Icons.Default.Folder,
                                title = "Folder is empty",
                                description = if (searchQuery.isBlank()) "No videos found in this folder." else "No videos matched \"$searchQuery\" in this folder."
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 16.dp + playerPadding),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredVideosInFolder, key = { it.id }) { video ->
                                    LocalSongItem(
                                        song = video,
                                        isPlaying = false,
                                        onClick = { onVideoClick(video) },
                                        onLongClick = { },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Crossfade(
                        targetState = selectedMediaType,
                        label = "MediaTypeCrossfade"
                    ) { mediaType ->
                        if (mediaType == LocalMediaType.YOUTUBE) {
                            YouTubeVideoSearch()
                        } else if (mediaType == LocalMediaType.MOVIES) {
                            val binder = LocalPlayerServiceBinder.current
                            MovieBoxBrowse(
                                viewModel = viewModel,
                                onPlayStream = { stream, title, posterUrl, quality ->
                                    val extras = android.os.Bundle().apply {
                                        putBoolean("forceVideo", true)
                                        putBoolean("isLocal", true)
                                        putString("streamCookie", stream.signCookie)
                                        putString("streamUserAgent", stream.userAgent)
                                        putString("preferredResolution", quality)
                                    }
                                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                                        .setUri(stream.manifestUrl)
                                        .setMediaId(stream.manifestUrl)
                                        .setMimeType(if (stream.manifestUrl.endsWith(".mpd")) androidx.media3.common.MimeTypes.APPLICATION_MPD else androidx.media3.common.MimeTypes.VIDEO_MP4)
                                        .setMediaMetadata(
                                            androidx.media3.common.MediaMetadata.Builder()
                                                .setTitle(title)
                                                .setArtist("MovieBox Stream • $quality")
                                                .setArtworkUri(posterUrl?.let { android.net.Uri.parse(it) })
                                                .setExtras(extras)
                                                .build()
                                        )
                                        .build()

                                    com.github.musicyou.service.PlayerService.currentStreamCookie = stream.signCookie
                                    com.github.musicyou.service.PlayerService.currentStreamUserAgent = stream.userAgent
                                    com.github.musicyou.service.PlayerService.currentPreferredResolution = quality
                                    binder?.syncLoadMediaItem(mediaItem, 0L, autoPlay = true)
                                }
                            )
                        } else if (!hasPermission) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Permission required to access local videos")
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { launcher.launch(permissions) }) {
                                        Text("Grant Permission")
                                    }
                                }
                            }
                        } else {
                            when (mediaType) {
                                LocalMediaType.ALL -> {
                                    if (filteredCombinedMedia.isEmpty()) {
                                        EmptyMediaState(
                                            icon = Icons.Default.VideoLibrary,
                                            title = if (searchQuery.isNotBlank()) "No matching media" else "No local media found",
                                            description = if (searchQuery.isNotBlank()) "No files matched \"$searchQuery\"." else "No videos or music tracks found in your local storage.",
                                            actionText = "Refresh Storage",
                                            onAction = { viewModel.loadMedia() },
                                            secondaryActionText = "Browse Movies & Shows",
                                            onSecondaryAction = { setSelectedMediaType(LocalMediaType.MOVIES) }
                                        )
                                    } else {
                                        LazyColumn(
                                            contentPadding = PaddingValues(
                                                bottom = 16.dp + playerPadding,
                                                top = 8.dp
                                            ),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(filteredCombinedMedia, key = { it.id }) { item ->
                                                LocalSongItem(
                                                    song = item,
                                                    isPlaying = false,
                                                    onClick = { onVideoClick(item) },
                                                    onLongClick = { },
                                                    modifier = Modifier.animateItem()
                                                )
                                            }
                                        }
                                    }
                                }
                                LocalMediaType.FOLDERS -> {
                                    if (filteredVideoFolders.isEmpty()) {
                                        EmptyMediaState(
                                            icon = Icons.Default.Folder,
                                            title = if (searchQuery.isNotBlank()) "No matching folders" else "No video folders found",
                                            description = if (searchQuery.isNotBlank()) "No folders matched \"$searchQuery\"." else "Folders containing videos on your device storage will appear here.",
                                            actionText = "Refresh Storage",
                                            onAction = { viewModel.loadMedia() },
                                            secondaryActionText = "Browse Movies & Shows",
                                            onSecondaryAction = { setSelectedMediaType(LocalMediaType.MOVIES) }
                                        )
                                    } else {
                                        LazyColumn(
                                            contentPadding = PaddingValues(
                                                bottom = 16.dp + playerPadding,
                                                top = 8.dp
                                            ),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(filteredVideoFolders, key = { it.name }) { folder ->
                                                FolderItem(
                                                    folder = folder,
                                                    onClick = { 
                                                        selectedFolder = folder
                                                        focusManager.clearFocus()
                                                    },
                                                    modifier = Modifier.animateItem()
                                                )
                                            }
                                        }
                                    }
                                }
                                LocalMediaType.MUSIC -> {
                                    if (filteredAllMusic.isEmpty()) {
                                        EmptyMediaState(
                                            icon = Icons.Filled.MusicNote,
                                            title = if (searchQuery.isNotBlank()) "No matching music" else "No local music found",
                                            description = if (searchQuery.isNotBlank()) "No songs matched \"$searchQuery\"." else "Local audio and music tracks will appear here.",
                                            actionText = "Refresh Storage",
                                            onAction = { viewModel.loadMedia() },
                                            secondaryActionText = "Explore YouTube",
                                            onSecondaryAction = { setSelectedMediaType(LocalMediaType.YOUTUBE) }
                                        )
                                    } else {
                                        LazyColumn(
                                            contentPadding = PaddingValues(
                                                bottom = 16.dp + playerPadding,
                                                top = 8.dp
                                            ),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(filteredAllMusic, key = { it.id }) { music ->
                                                LocalSongItem(
                                                    song = music,
                                                    isPlaying = false,
                                                    onClick = { onVideoClick(music) },
                                                    onLongClick = { },
                                                    modifier = Modifier.animateItem()
                                                )
                                            }
                                        }
                                    }
                                }
                                LocalMediaType.YOUTUBE, LocalMediaType.MOVIES -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun EmptyMediaState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(text = actionText, fontWeight = FontWeight.SemiBold)
                }
            }
            if (secondaryActionText != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSecondaryAction) {
                    Text(text = secondaryActionText)
                }
            }
        }
    }
}

@Composable
fun FolderItem(
    folder: VideoFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${folder.videos.size} Videos",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeVideoSearch() {
    val binder = LocalPlayerServiceBinder.current
    val playerPadding = LocalPlayerPadding.current
    val viewModel: VideosViewModel = viewModel()
    val query by viewModel.ytSearchQuery.collectAsState()
    val setQuery: (String) -> Unit = { viewModel.ytSearchQuery.value = it }
    
    val searchResults by viewModel.ytSearchResults.collectAsState()
    val setSearchResults: (List<androidx.media3.common.MediaItem>) -> Unit = { viewModel.ytSearchResults.value = it }
    
    val isLoading by viewModel.ytIsLoading.collectAsState()
    val setIsLoading: (Boolean) -> Unit = { viewModel.ytIsLoading.value = it }
    
    val hasSearched by viewModel.ytHasSearched.collectAsState()
    val setHasSearched: (Boolean) -> Unit = { viewModel.ytHasSearched.value = it }
    
    val errorMessage by viewModel.ytErrorMessage.collectAsState()
    val setErrorMessage: (String?) -> Unit = { viewModel.ytErrorMessage.value = it }
    
    val suggestionsResult by viewModel.ytSuggestionsResult.collectAsState()
    val setSuggestionsResult: (Result<List<String>>?) -> Unit = { viewModel.ytSuggestionsResult.value = it }
    
    val trendingVideos by viewModel.ytTrendingVideos.collectAsState()
    val isTrendingLoading by viewModel.ytTrendingLoading.collectAsState()
    val selectedCategory by viewModel.ytSelectedCategory.collectAsState()

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (trendingVideos.isEmpty()) {
            viewModel.loadYouTubeTrending()
        }
    }

    LaunchedEffect(query) {
        setSuggestionsResult(if (query.isNotEmpty()) {
            kotlinx.coroutines.delay(200)
            Innertube.searchSuggestions(input = query) as Result<List<String>>
        } else null)
    }

    val performSearch = { searchQuery: String ->
        val effectiveQuery = searchQuery.ifBlank { query }
        if (effectiveQuery.isNotBlank()) {
            focusManager.clearFocus()
            coroutineScope.launch(Dispatchers.IO) {
                setIsLoading(true)
                setErrorMessage(null)
                try {
                    val videoId = extractYouTubeId(effectiveQuery)
                    android.util.Log.d("YouTubeSearch", "Parsed videoId: $videoId from query: $effectiveQuery")
                    if (videoId != null) {
                        val songResult = Innertube.song(videoId)
                        android.util.Log.d("YouTubeSearch", "Song result for $videoId: ${songResult?.getOrNull()}")
                        songResult?.getOrNull()?.let { song ->
                            android.util.Log.d("YouTubeSearch", "Song details: name=${song.info?.name}, authors=${song.authors}")
                            val mediaItem = song.asVideoMediaItem
                            android.util.Log.d("YouTubeSearch", "Created MediaItem: id=${mediaItem.mediaId}, title=${mediaItem.mediaMetadata.title}, artist=${mediaItem.mediaMetadata.artist}")
                            setSearchResults(listOf(mediaItem))
                            withContext(Dispatchers.Main) {
                                android.util.Log.d("YouTubeSearch", "Calling forcePlay for MediaItem: ${mediaItem.mediaId}")
                                val embedMediaItem = if (mediaItem.mediaId.startsWith("youtube-embed:")) {
                                    mediaItem
                                } else {
                                    mediaItem.buildUpon().setMediaId("youtube-embed:${mediaItem.mediaId}").build()
                                }
                                binder?.syncLoadMediaItem(embedMediaItem, autoPlay = true)
                            }
                        } ?: run {
                            android.util.Log.d("YouTubeSearch", "Song result was null or empty for videoId: $videoId")
                            setSearchResults(emptyList())
                        }
                    } else {
                        val searchResult = Innertube.searchPage(
                            query = effectiveQuery,
                            params = Innertube.SearchFilter.Video.value,
                            fromMusicShelfRendererContent = { Innertube.VideoItem.from(it) }
                        )
                        android.util.Log.d("YouTubeSearch", "Search result isSuccess=${searchResult?.isSuccess}, exception=${searchResult?.exceptionOrNull()}")
                        
                        val searchPage = searchResult?.getOrNull()
                        android.util.Log.d("YouTubeSearch", "Search page returned ${searchPage?.items?.size} items")
                        setSearchResults(searchPage?.items?.map { it.asVideoMediaItem } ?: emptyList())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("YouTubeSearch", "Error during YouTube search", e)
                    setErrorMessage("Search failed. Check your connection.")
                    setSearchResults(emptyList())
                } finally {
                    setIsLoading(false)
                    setHasSearched(true)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Paste YouTube link...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        setQuery("")
                        setSearchResults(emptyList())
                        setHasSearched(false)
                        setErrorMessage(null)
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { performSearch("") }
            ),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(onClick = { performSearch("") }) {
                        Text("Retry")
                    }
                }
            }
        } else if (hasSearched && searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Try a different search or paste a YouTube link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (searchResults.isNotEmpty()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp + playerPadding),
                modifier = Modifier.fillMaxSize()
            ) {
                items(searchResults) { mediaItem ->
                    MediaSongItem(
                        song = mediaItem,
                        onClick = { 
                            android.util.Log.d("YouTubeSearch", "Clicked song: ${mediaItem.mediaMetadata.title}, mediaId: ${mediaItem.mediaId}")
                            // Add the special prefix so the sync engine knows this is an embedded video request
                            val embedMediaItem = if (mediaItem.mediaId.startsWith("youtube-embed:")) {
                                mediaItem
                            } else {
                                mediaItem.buildUpon().setMediaId("youtube-embed:${mediaItem.mediaId}").build()
                            }
                            android.util.Log.d("YouTubeSearch", "Calling hybridPlaybackEngine.loadMediaItem for: ${embedMediaItem.mediaId}")
                            binder?.syncLoadMediaItem(embedMediaItem, 0, true)
                        }
                    )
                }
            }
        } else if (!hasSearched && query.isNotEmpty() && suggestionsResult?.getOrNull()?.isNotEmpty() == true) {
            val suggestions = suggestionsResult?.getOrNull() ?: emptyList()
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp + playerPadding),
                modifier = Modifier.fillMaxSize()
            ) {
                items(suggestions) { suggestion ->
                    ListItem(
                        headlineContent = { Text(text = suggestion) },
                        leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.clickable {
                            setQuery(suggestion)
                            performSearch(suggestion)
                        }
                    )
                }
            }
        } else {
            val categories = listOf("🔥 Trending", "🎵 Top Hits", "🎧 Hip Hop", "⚡ Pop", "🎸 Rock", "✨ New")
            
            Column(modifier = Modifier.fillMaxSize()) {
                // Category Filter Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        FilterPill(
                            text = category,
                            selected = selectedCategory == category,
                            onClick = {
                                if (selectedCategory != category) {
                                    viewModel.loadYouTubeTrending(category)
                                }
                            }
                        )
                    }
                }

                if (isTrendingLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (trendingVideos.isNotEmpty()) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp + playerPadding),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_youtube),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = androidx.compose.ui.graphics.Color.Unspecified
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$selectedCategory Videos",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }

                        items(trendingVideos, key = { it.mediaId }) { mediaItem ->
                            MediaSongItem(
                                song = mediaItem,
                                onClick = {
                                    val embedMediaItem = if (mediaItem.mediaId.startsWith("youtube-embed:")) {
                                        mediaItem
                                    } else {
                                        mediaItem.buildUpon().setMediaId("youtube-embed:${mediaItem.mediaId}").build()
                                    }
                                    binder?.syncLoadMediaItem(embedMediaItem, 0, true)
                                }
                            )
                        }
                    }
                } else {
                    EmptyMediaState(
                        icon = Icons.Default.Search,
                        title = "Unable to load videos",
                        description = "Could not fetch YouTube videos right now. Check your connection.",
                        actionText = "Try Again",
                        onAction = { viewModel.loadYouTubeTrending(selectedCategory) }
                    )
                }
            }
        }
    }
}

fun extractYouTubeId(url: String): String? {
    val patterns = listOf(
        "(?:https?://)?(?:www\\.)?youtube\\.com/watch\\?.*v=([a-zA-Z0-9_-]{11})",
        "(?:https?://)?(?:www\\.)?youtube\\.com/embed/([a-zA-Z0-9_-]{11})",
        "(?:https?://)?(?:www\\.)?youtube\\.com/v/([a-zA-Z0-9_-]{11})",
        "(?:https?://)?(?:www\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})",
        "(?:https?://)?youtu\\.be/([a-zA-Z0-9_-]{11})",
        "(?:https?://)?(?:www\\.)?youtube-nocookie\\.com/embed/([a-zA-Z0-9_-]{11})",
        "(?:https?://)?m\\.youtube\\.com/watch\\?.*v=([a-zA-Z0-9_-]{11})"
    )
    for (pattern in patterns) {
        val matcher = java.util.regex.Pattern.compile(pattern).matcher(url)
        if (matcher.find()) return matcher.group(1)
    }
    // Fallback: check if input is a bare 11-char video ID
    if (url.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return url
    return null
}
