package com.github.musicyou.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.core.content.edit
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.ui.styling.*
import com.github.musicyou.utils.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val colors = rememberNeumorphicColors()
    val scope = rememberCoroutineScope()
    val firebaseUser = remember { Firebase.auth.currentUser }
    
    var step by remember { mutableStateOf(1) }
    var isLoadingInitialData by remember { mutableStateOf(true) }
    
    // Preferences / State
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("US") }
    val selectedGenres = remember { mutableStateListOf<String>() }
    
    // Username Logic States
    var isExistingUser by remember { mutableStateOf(false) }
    var isCheckingUsername by remember { mutableStateOf(false) }
    var isUsernameAvailable by remember { mutableStateOf(false) }

    // Initial Data Fetch
    LaunchedEffect(Unit) {
        // Try to fetch existing profile from Firestore
        val found = ProfileManager.fetchUserProfile(context)
        if (found) {
            val prefs = context.preferences
            name = prefs.getString(displayNameKey, "") ?: ""
            username = prefs.getString(usernameKey, "") ?: ""
            selectedRegion = prefs.getString(contentRegionKey, "US") ?: "US"
            val genresStr = prefs.getString(favoriteGenresKey, "") ?: ""
            if (genresStr.isNotBlank()) {
                selectedGenres.clear()
                selectedGenres.addAll(genresStr.split(","))
            }
            isExistingUser = username.isNotBlank()
            isUsernameAvailable = isExistingUser
        } else {
            // Fallback to Google Display Name for new users
            name = firebaseUser?.displayName ?: ""
        }
        isLoadingInitialData = false
    }

    // Username checking effect
    LaunchedEffect(username) {
        if (isExistingUser && username == context.preferences.getString(usernameKey, "")) {
            isCheckingUsername = false
            isUsernameAvailable = true
            return@LaunchedEffect
        }

        if (username.length >= 3) {
            isCheckingUsername = true
            delay(500) // Debounce
            isUsernameAvailable = ProfileManager.isUsernameAvailable(username)
            isCheckingUsername = false
        } else {
            isUsernameAvailable = false
        }
    }

    val regions = listOf(
        "US" to "United States",
        "IN" to "India",
        "GB" to "United Kingdom",
        "BR" to "Brazil",
        "DE" to "Germany",
        "JP" to "Japan",
        "KR" to "South Korea",
        "FR" to "France"
    )

    val genres = listOf(
        "romantic" to "Romantic",
        "party" to "Party",
        "soulful" to "Soulful",
        "pop" to "Pop",
        "rock" to "Rock",
        "hiphop" to "Hip Hop",
        "lofi" to "Lofi",
        "chill" to "Chill"
    )

    Surface(
        color = colors.background,
        modifier = Modifier.fillMaxSize()
    ) {
        if (isLoadingInitialData) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = neonPurple)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedContent(targetState = step, label = "OnboardingStep", modifier = Modifier.weight(1f)) { currentStep ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (currentStep) {
                            1 -> WelcomeStep(
                                name = name,
                                photoUrl = firebaseUser?.photoUrl?.toString(),
                                onNameChange = { name = it }
                            )
                            2 -> UsernameStep(
                                username = username,
                                onUsernameChange = { username = it },
                                isExistingUser = isExistingUser,
                                isChecking = isCheckingUsername,
                                isAvailable = isUsernameAvailable
                            )
                            3 -> RegionStep(regions, selectedRegion) { selectedRegion = it }
                            4 -> GenreStep(genres, selectedGenres)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Navigation Button
                val canGoNext = when(step) {
                    1 -> name.isNotBlank()
                    2 -> isUsernameAvailable
                    else -> true
                }

                NeumorphicIconButton(
                    onClick = {
                        if (step < 4) {
                            step++
                        } else {
                            // FINAL STEP: Save to Firestore
                            scope.launch {
                                // 1. Local Preferences first for instant UI response
                                context.preferences.edit {
                                    putString(displayNameKey, name.trim())
                                    putString(usernameKey, username.trim().lowercase())
                                    putString(contentRegionKey, selectedRegion)
                                    putString(favoriteGenresKey, selectedGenres.joinToString(","))
                                    putBoolean(onboardedKey, true)
                                }
                                
                                // 2. Firestore Sync
                                ProfileManager.registerUserProfile(
                                    context = context,
                                    displayName = name.trim(),
                                    username = username.trim().lowercase(),
                                    photoUrl = firebaseUser?.photoUrl?.toString(),
                                    region = selectedRegion,
                                    vibes = selectedGenres.toList()
                                )
                                
                                onComplete()
                            }
                        }
                    },
                    icon = if (step < 4) Icons.Default.ArrowForward else Icons.Default.Check,
                    size = 64.dp,
                    iconSize = 32.dp,
                    modifier = Modifier.then(if (!canGoNext) Modifier.alpha(0.5f) else Modifier)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Step $step of 4",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun WelcomeStep(name: String, photoUrl: String?, onNameChange: (String) -> Unit) {
    val colors = rememberNeumorphicColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Show Profile Picture if available
        Box(
            modifier = Modifier
                .size(100.dp)
                .neumorphicRaised(cornerRadius = 50.dp)
                .clip(CircleShape)
                .background(colors.background)
        ) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Profile",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Welcome to Musica",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Let's personalize your experience.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))
        
        // Custom Styled TextField
        Text(
            text = "DISPLAY NAME",
            style = MaterialTheme.typography.labelMedium,
            color = neonPurple,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .neumorphicPressed(cornerRadius = 16.dp)
                .background(colors.background, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (name.isEmpty()) {
                Text("Your Name", color = colors.onBackground.copy(alpha = 0.4f))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = name,
                onValueChange = onNameChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
fun UsernameStep(
    username: String,
    onUsernameChange: (String) -> Unit,
    isExistingUser: Boolean,
    isChecking: Boolean,
    isAvailable: Boolean
) {
    val colors = rememberNeumorphicColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Pick a unique @username",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .neumorphicPressed(cornerRadius = 16.dp)
                .background(colors.background, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@", color = neonPurple, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (username.isEmpty()) {
                        Text("username", color = colors.onBackground.copy(alpha = 0.4f))
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isExistingUser // Disable if already has one
                    )
                }
                
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = neonPurple)
                } else if (username.length >= 3) {
                    Text(
                        text = if (isAvailable) "✓" else "✕",
                        color = if (isAvailable) Color.Green else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isExistingUser && isAvailable) {
            Text(
                text = "Username recognized, welcome back!",
                color = Color.Green,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        } else if (username.isNotEmpty() && username.length < 3) {
            Text("Minimum 3 characters", color = Color.Red, style = MaterialTheme.typography.bodySmall)
        } else if (!isAvailable && username.isNotEmpty() && !isChecking) {
            Text("Username already taken", color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RegionStep(regions: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val colors = rememberNeumorphicColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Where are you from?",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        LazyColumn(
            modifier = Modifier.height(300.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(regions) { (code, name) ->
                val isSelected = selected == code
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(code) }
                        .then(
                            if (isSelected) Modifier.neumorphicPressed(cornerRadius = 12.dp)
                            else Modifier.neumorphicRaised(cornerRadius = 12.dp)
                        )
                        .background(colors.background)
                        .padding(16.dp)
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else colors.onBackground,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun GenreStep(genres: List<Pair<String, String>>, selected: MutableList<String>) {
    val colors = rememberNeumorphicColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Pick your vibe",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = genres.chunked(2)
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (id, label) ->
                        val isSelected = selected.contains(id)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isSelected) selected.remove(id) else selected.add(id)
                                }
                                .then(
                                    if (isSelected) Modifier.neumorphicPressed(cornerRadius = 12.dp)
                                    else Modifier.neumorphicRaised(cornerRadius = 12.dp)
                                )
                                .background(colors.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else colors.onBackground,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
