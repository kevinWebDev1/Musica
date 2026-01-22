package com.github.musicyou.ui.styling

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

// ============================================================================
// NEON GLASS COLORS
// ============================================================================
val neonPink = Color(0xFFFF2D88)
val neonPurple = Color(0xFF9D4EDD)
val purplishPink = Color(0xFFD633B0) // Blend of Neon Pink and Purple
val glassBg = Color(0x14FFFFFF)         // rgba(255, 255, 255, 0.08)
val glassBorder = Color(0x26FFFFFF)     // rgba(255, 255, 255, 0.15)
val glassHighlight = Color(0x33FFFFFF)  // rgba(255, 255, 255, 0.2)
val textSecondary = Color(0xFFB3B3B3)
val bgDarkSurface = Color(0xFF2C2C2E)

// ============================================================================
// NEUMORPHIC COLOR PALETTE
// Pure neumorphic colors for soft UI design
// ============================================================================

// Light Theme - Warm Gray Neumorphic
val neumorphicBackgroundLight = Color(0xFFE4E0DA)      // Main neumorphic background
val neumorphicSurfaceLight = Color(0xFFE4E0DA)         // Surface matches background
val neumorphicOnBackgroundLight = Color(0xFF2E2E2E)   // Dark text on light background
val neumorphicSurfaceContainerLight = Color(0xFFE4E0DA)
val neumorphicSurfaceContainerHighLight = Color(0xFFDAD6D0)
val neumorphicSurfaceContainerLowLight = Color(0xFFEEEAE4)

// Dark Theme - Charcoal Neumorphic  
val neumorphicBackgroundDark = Color(0xFF2E2E2E)       // Main neumorphic background
val neumorphicSurfaceDark = Color(0xFF2E2E2E)          // Surface matches background
val neumorphicOnBackgroundDark = Color(0xFFE4E0DA)    // Light text on dark background
val neumorphicSurfaceContainerDark = Color(0xFF2E2E2E)
val neumorphicSurfaceContainerHighDark = Color(0xFF383838)
val neumorphicSurfaceContainerLowDark = Color(0xFF242424)

// ============================================================================
// ACCENT COLORS (kept for primary, error, etc.)
// ============================================================================

// Primary - Soft blue accent
val primaryLight = Color(0xFF5B7DB1)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFD4E3FF)
val onPrimaryContainerLight = Color(0xFF001C3B)

val primaryDark = Color(0xFFA8C8FF)
val onPrimaryDark = Color(0xFF003060)
val primaryContainerDark = Color(0xFF194778)
val onPrimaryContainerDark = Color(0xFFD4E3FF)

// Secondary - Muted complement
val secondaryLight = Color(0xFF6B7280)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFD1D5DB)
val onSecondaryContainerLight = Color(0xFF1F2937)

val secondaryDark = Color(0xFFD1D5DB)
val onSecondaryDark = Color(0xFF1F2937)
val secondaryContainerDark = Color(0xFF4B5563)
val onSecondaryContainerDark = Color(0xFFE5E7EB)

// Tertiary - Warm accent
val tertiaryLight = Color(0xFF8B7355)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFFFDCC2)
val onTertiaryContainerLight = Color(0xFF2E1500)

val tertiaryDark = Color(0xFFE5BFA0)
val onTertiaryDark = Color(0xFF442A10)
val tertiaryContainerDark = Color(0xFF5E4129)
val onTertiaryContainerDark = Color(0xFFFFDCC2)

// Error - Soft red
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF410002)

val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)

// ============================================================================
// SEEKBAR SPECIFIC COLORS
// These are tuned specifically for optimal neumorphic seekbar appearance
// ============================================================================

// Light Theme - Seekbar
val seekbarTrackLight = Color(0xFFCBC6BF)      // Darker than background for sunken effect
val seekbarTrackShadowLight = Color(0xFFB5B0A9) // Even darker for deeper shadow
val seekbarProgressLight = Color(0xFF5B7DB1)   // Your primary blue
val seekbarThumbLight = Color(0xFFE4E0DA)      // Same as background

// Dark Theme - Seekbar
val seekbarTrackDark = Color(0xFF363636)       // Lighter than background for sunken effect
val seekbarTrackShadowDark = Color(0xFF444444) // Lighter for highlight in dark theme
val seekbarProgressDark = Color(0xFFA8C8FF)    // Your primary blue (dark theme)
val seekbarThumbDark = Color(0xFF2E2E2E)       // Same as background

// ============================================================================
// COMPLETE LIGHT THEME COLORS
// ============================================================================

val backgroundLight = neumorphicBackgroundLight
val onBackgroundLight = neumorphicOnBackgroundLight
val surfaceLight = neumorphicSurfaceLight
val onSurfaceLight = neumorphicOnBackgroundLight
val surfaceVariantLight = Color(0xFFD8D4CE)
val onSurfaceVariantLight = Color(0xFF49454F)
val outlineLight = Color(0xFF79747E)
val outlineVariantLight = Color(0xFFCAC4D0)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF313033)
val inverseOnSurfaceLight = Color(0xFFF4EFF4)
val inversePrimaryLight = Color(0xFFA8C8FF)
val surfaceDimLight = Color(0xFFD8D4CE)
val surfaceBrightLight = Color(0xFFF0ECE6)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = neumorphicSurfaceContainerLowLight
val surfaceContainerLight = neumorphicSurfaceContainerLight
val surfaceContainerHighLight = neumorphicSurfaceContainerHighLight
val surfaceContainerHighestLight = Color(0xFFD0CCC6)

// ============================================================================
// COMPLETE DARK THEME COLORS
// ============================================================================

val backgroundDark = neumorphicBackgroundDark
val onBackgroundDark = neumorphicOnBackgroundDark
val surfaceDark = neumorphicSurfaceDark
val onSurfaceDark = neumorphicOnBackgroundDark
val surfaceVariantDark = Color(0xFF49454F)
val onSurfaceVariantDark = Color(0xFFCAC4D0)
val outlineDark = Color(0xFF938F99)
val outlineVariantDark = Color(0xFF49454F)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFE6E1E5)
val inverseOnSurfaceDark = Color(0xFF313033)
val inversePrimaryDark = Color(0xFF5B7DB1)
val surfaceDimDark = Color(0xFF242424)
val surfaceBrightDark = Color(0xFF3B3B3B)
val surfaceContainerLowestDark = Color(0xFF1A1A1A)
val surfaceContainerLowDark = neumorphicSurfaceContainerLowDark
val surfaceContainerDark = neumorphicSurfaceContainerDark
val surfaceContainerHighDark = neumorphicSurfaceContainerHighDark
val surfaceContainerHighestDark = Color(0xFF424242)

// ============================================================================
// EXTENSION PROPERTIES
// ============================================================================

val ColorScheme.shimmer: Color
    get() = Color(0xFF838383)

val ColorScheme.overlay: Color
    get() = Color.Black.copy(alpha = Dimensions.mediumOpacity)

val ColorScheme.onOverlay: Color
    get() = Color(0xFFE1E1E2)

// Extension properties
val ColorScheme.seekbarTrack: Color
    @Composable
    get() = if (isSystemInDarkTheme()) seekbarTrackDark else seekbarTrackLight

val ColorScheme.seekbarProgress: Color
    @Composable
    get() = if (isSystemInDarkTheme()) primaryDark else primaryLight

val ColorScheme.seekbarThumb: Color
    @Composable
    get() = if (isSystemInDarkTheme()) neumorphicBackgroundDark else neumorphicBackgroundLight