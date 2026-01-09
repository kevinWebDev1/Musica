package com.github.musicyou.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.core.content.edit
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.unit.sp
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import com.github.musicyou.ui.styling.neumorphicSurface
import com.github.musicyou.ui.styling.neumorphicRaised
import com.github.musicyou.ui.styling.neumorphicPressed
import com.github.musicyou.ui.styling.NeumorphicIconButton
import com.github.musicyou.utils.*

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val colors = rememberNeumorphicColors()
    
    var step by remember { mutableStateOf(1) }
    
    // Preferences
    var name by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("US") }
    val selectedGenres = remember { mutableStateListOf<String>() }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(targetState = step, label = "OnboardingStep") { currentStep ->
                when (currentStep) {
                    1 -> WelcomeStep(name) { name = it }
                    2 -> RegionStep(regions, selectedRegion) { selectedRegion = it }
                    3 -> GenreStep(genres, selectedGenres)
                    else -> Box {}
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Navigation Button
            NeumorphicIconButton(
                onClick = {
                    if (step < 3) {
                        step++
                    } else {
                        // Save all preferences including name for sync
                        if (name.isNotBlank()) {
                            com.github.musicyou.sync.SyncPreferences.setUserName(context, name.trim())
                        }
                        context.preferences.edit {
                            putString(contentRegionKey, selectedRegion)
                            putString(favoriteGenresKey, selectedGenres.joinToString(","))
                            putBoolean(onboardedKey, true)
                        }
                        onComplete()
                    }
                },
                icon = if (step < 3) Icons.Default.ArrowForward else Icons.Default.Check,
                size = 64.dp,
                iconSize = 32.dp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Step $step of 3",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun WelcomeStep(name: String, onNameChange: (String) -> Unit) {
    val colors = rememberNeumorphicColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Welcome to Musica",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Let's personalize your experience.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))
        
        // Custom Styled TextField using Neumorphic container
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

        // Grid-like layout for genres
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
