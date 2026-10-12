package com.github.musicyou.ui.screens.home

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.providers.moviebox.MovieBoxClient
import com.github.musicyou.providers.moviebox.MovieBoxItem
import com.github.musicyou.providers.moviebox.MovieBoxSeason
import com.github.musicyou.providers.moviebox.MovieBoxStream
import com.github.musicyou.viewmodels.VideosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieBoxBrowse(
    viewModel: VideosViewModel,
    onPlayStream: (stream: MovieBoxStream, title: String, posterUrl: String?, quality: String) -> Unit
) {
    val playerPadding = LocalPlayerPadding.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val searchQuery by viewModel.mbSearchQuery.collectAsState()
    val searchResults by viewModel.mbSearchResults.collectAsState()
    val trendingResults by viewModel.mbTrendingResults.collectAsState()
    val hasSearched by viewModel.mbHasSearched.collectAsState()
    val isLoading by viewModel.mbIsLoading.collectAsState()
    val errorMessage by viewModel.mbErrorMessage.collectAsState()

    var selectedItemForDetail by remember { mutableStateOf<MovieBoxItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadMovieBoxTrending()
    }

    val displayItems = if (hasSearched) searchResults else trendingResults

    val categoryTags = listOf(
        "🔥 Trending" to "",
        "🎬 Action" to "Action",
        "🎭 Comedy" to "Comedy",
        "🚀 Sci-Fi" to "Sci-Fi",
        "🦸 Marvel" to "Marvel",
        "👻 Horror" to "Horror",
        "🏯 Anime" to "Anime",
        "✨ 2026 Hits" to "2026"
    )
    var selectedCategoryTag by remember { mutableStateOf("🔥 Trending") }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.mbSearchQuery.value = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Search movies, shows & anime...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search Movies")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        viewModel.mbSearchQuery.value = ""
                        viewModel.mbHasSearched.value = false
                        viewModel.mbSearchResults.value = emptyList()
                        viewModel.mbErrorMessage.value = null
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    if (searchQuery.isNotBlank()) {
                        viewModel.searchMovieBox(searchQuery)
                    }
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )

        // Quick Category Tag Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categoryTags.forEach { (label, query) ->
                val isSelected = selectedCategoryTag == label && (!hasSearched || searchQuery == query)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        selectedCategoryTag = label
                        focusManager.clearFocus()
                        if (query.isEmpty()) {
                            viewModel.mbSearchQuery.value = ""
                            viewModel.mbHasSearched.value = false
                            viewModel.loadMovieBoxTrending()
                        } else {
                            viewModel.mbSearchQuery.value = query
                            viewModel.searchMovieBox(query)
                        }
                    }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content Area
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = playerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (hasSearched) "Searching MovieBox..." else "Fetching trending titles...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = playerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Failed to load content",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = {
                            if (searchQuery.isNotBlank()) {
                                viewModel.searchMovieBox(searchQuery)
                            } else {
                                viewModel.loadMovieBoxTrending()
                            }
                        }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (hasSearched && displayItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = playerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No movies or shows found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Try a different keyword or category tag",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 145.dp),
                    contentPadding = PaddingValues(bottom = 16.dp + playerPadding, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayItems, key = { it.id }) { item ->
                        MoviePosterCard(
                            item = item,
                            onClick = { selectedItemForDetail = item }
                        )
                    }
                }
            }
        }
    }

    // Detail & Stream Picker BottomSheet
    selectedItemForDetail?.let { item ->
        MovieDetailBottomSheet(
            item = item,
            onDismiss = { selectedItemForDetail = null },
            onPlayStream = { stream, title, poster, quality ->
                selectedItemForDetail = null
                onPlayStream(stream, title, poster, quality)
            }
        )
    }
}

@Composable
fun MoviePosterCard(
    item: MovieBoxItem,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color.Black.copy(alpha = 0.2f))
            ) {
                if (!item.posterUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isSeries) Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }

                // Gradient overlay at the bottom of the poster
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                )

                // Top Left: Media Type & Highest Quality Tag
                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        color = if (item.isSeries) Color(0xFF673AB7).copy(alpha = 0.9f) else Color(0xFFE91E63).copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = if (item.isSeries) "SERIES" else "MOVIE",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    val qualityColor = when (item.maxQuality.uppercase()) {
                        "4K" -> Color(0xFF00897B)
                        "CAM" -> Color(0xFFE65100)
                        "720P" -> Color(0xFF1976D2)
                        else -> Color(0xFF0288D1)
                    }

                    Surface(
                        shape = RoundedCornerShape(bottomEnd = 6.dp, bottomStart = 2.dp),
                        color = qualityColor.copy(alpha = 0.92f)
                    ) {
                        Text(
                            text = item.maxQuality,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                // Top Right: Rating Tag (if available)
                if (!item.rating.isNullOrEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = item.rating,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.year ?: (if (item.isSeries) "TV Show" else "Movie"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.duration != null) {
                        Text(
                            text = item.duration,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailBottomSheet(
    item: MovieBoxItem,
    onDismiss: () -> Unit,
    onPlayStream: (stream: MovieBoxStream, title: String, posterUrl: String?, quality: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isDetailsLoading by remember { mutableStateOf(item.isSeries) }
    var seasons by remember { mutableStateOf<List<MovieBoxSeason>>(emptyList()) }
    var selectedSeasonNum by remember { mutableStateOf(1) }
    var fullItem by remember { mutableStateOf(item) }

    var isStreamResolving by remember { mutableStateOf(false) }
    var resolvingEpNumber by remember { mutableStateOf<Int?>(null) }

    var movieStreams by remember { mutableStateOf<List<MovieBoxStream>?>(null) }
    var availableResolutions by remember {
        mutableStateOf(
            if (item.maxQuality.equals("CAM", ignoreCase = true)) listOf("CAM")
            else listOf("1080p", "720p", "480p")
        )
    }
    var selectedResolution by remember {
        mutableStateOf(if (item.maxQuality.equals("CAM", ignoreCase = true)) "CAM" else "1080p")
    }

    LaunchedEffect(item.id) {
        if (item.isSeries) {
            isDetailsLoading = true
            val res = MovieBoxClient.getDetails(item.id)
            isDetailsLoading = false
            if (res.isSuccess) {
                val (detailedItem, loadedSeasons) = res.getOrThrow()
                fullItem = detailedItem
                seasons = loadedSeasons
                if (loadedSeasons.isNotEmpty()) {
                    selectedSeasonNum = loadedSeasons.first().seasonNumber
                }
            }
        } else {
            isStreamResolving = true
            val streamRes = MovieBoxClient.getStreams(item.id, season = 0, episode = 0)
            isStreamResolving = false
            if (streamRes.isSuccess) {
                val streams = streamRes.getOrThrow()
                movieStreams = streams
                val resList = streams.firstOrNull()?.availableResolutions ?: emptyList()
                if (resList.isNotEmpty()) {
                    availableResolutions = resList
                    if (selectedResolution !in resList) {
                        selectedResolution = resList.first()
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            // Header Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Poster
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!fullItem.posterUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = fullItem.posterUrl,
                            contentDescription = fullItem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fullItem.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!fullItem.year.isNullOrEmpty()) {
                            Text(
                                text = fullItem.year!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!fullItem.rating.isNullOrEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFC107),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = fullItem.rating!!,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                        if (fullItem.duration != null) {
                            Text(
                                text = fullItem.duration!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (fullItem.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = fullItem.genres.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Synopsis
            if (!fullItem.description.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = fullItem.description!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Video Quality Selector Chips Row
            Text(
                text = "Video Quality",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableResolutions.forEach { res ->
                    val isSelected = res.equals(selectedResolution, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedResolution = res },
                        label = {
                            val labelText = when (res.uppercase()) {
                                "4K" -> "✨ 4K UHD"
                                "1080P" -> "💎 1080p FHD"
                                "720P" -> "⚡ 720p HD"
                                "480P" -> "📱 480p SD"
                                "CAM" -> "📹 CAM"
                                else -> res
                            }
                            Text(labelText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action: Movie Stream or TV Series Episode Selector
            if (!fullItem.isSeries) {
                // Direct Movie Stream Trigger
                Button(
                    onClick = {
                        val cached = movieStreams?.firstOrNull()
                        if (cached != null) {
                            onPlayStream(cached, fullItem.title, fullItem.posterUrl, selectedResolution)
                        } else {
                            scope.launch(Dispatchers.IO) {
                                isStreamResolving = true
                                val streamRes = MovieBoxClient.getStreams(fullItem.id, season = 0, episode = 0)
                                isStreamResolving = false

                                if (streamRes.isSuccess) {
                                    val streams = streamRes.getOrThrow()
                                    movieStreams = streams
                                    val bestStream = streams.firstOrNull()
                                    if (bestStream != null) {
                                        withContext(Dispatchers.Main) {
                                            onPlayStream(bestStream, fullItem.title, fullItem.posterUrl, selectedResolution)
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Stream unavailable: ${streamRes.exceptionOrNull()?.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isStreamResolving
                ) {
                    if (isStreamResolving) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Resolving $selectedResolution Stream...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play Movie ($selectedResolution Stream)", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // TV Series Season & Episode Picker
                if (isDetailsLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else if (seasons.isEmpty()) {
                    Text(
                        text = "Season info unavailable. Tap below to play Pilot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                isStreamResolving = true
                                val streamRes = MovieBoxClient.getStreams(fullItem.id, season = 1, episode = 1)
                                isStreamResolving = false
                                if (streamRes.isSuccess) {
                                    val stream = streamRes.getOrThrow().firstOrNull() ?: return@launch
                                    withContext(Dispatchers.Main) {
                                        onPlayStream(stream, "${fullItem.title} S01E01", fullItem.posterUrl, selectedResolution)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Play Episode 1 ($selectedResolution)")
                    }
                } else {
                    // Season selector pills
                    Text(
                        text = "Seasons",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        seasons.forEach { season ->
                            val isSelected = season.seasonNumber == selectedSeasonNum
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedSeasonNum = season.seasonNumber },
                                label = { Text("Season ${season.seasonNumber}") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Episode buttons
                    val currentSeason = seasons.firstOrNull { it.seasonNumber == selectedSeasonNum }
                    val episodes = currentSeason?.episodes ?: emptyList()

                    Text(
                        text = "Episodes (${episodes.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 75.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(episodes, key = { it.episodeNumber }) { ep ->
                            val isResolvingThis = isStreamResolving && resolvingEpNumber == ep.episodeNumber

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .height(44.dp)
                                    .clickable(enabled = !isStreamResolving) {
                                        resolvingEpNumber = ep.episodeNumber
                                        isStreamResolving = true
                                        scope.launch(Dispatchers.IO) {
                                            val streamRes = MovieBoxClient.getStreams(
                                                fullItem.id,
                                                season = selectedSeasonNum,
                                                episode = ep.episodeNumber
                                            )
                                            isStreamResolving = false
                                            resolvingEpNumber = null

                                            if (streamRes.isSuccess) {
                                                val stream = streamRes.getOrThrow().firstOrNull()
                                                if (stream != null) {
                                                    withContext(Dispatchers.Main) {
                                                        val epTitle = "${fullItem.title} S%02dE%02d".format(
                                                            selectedSeasonNum, ep.episodeNumber
                                                        )
                                                        onPlayStream(stream, epTitle, fullItem.posterUrl, selectedResolution)
                                                    }
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        "Episode stream unavailable: ${streamRes.exceptionOrNull()?.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (isResolvingThis) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = "EP ${ep.episodeNumber}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
