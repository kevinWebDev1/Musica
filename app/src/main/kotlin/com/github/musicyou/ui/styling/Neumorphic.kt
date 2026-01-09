package com.github.musicyou.ui.styling

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Light source direction for neumorphic shadows.
 * Determines which corner the light appears to come from.
 */
enum class LightSource {
    LEFT_TOP,
    RIGHT_TOP,
    LEFT_BOTTOM,
    RIGHT_BOTTOM
}

/**
 * Shape type for neumorphic elements.
 * - FLAT: Raised/extruded appearance (default)
 * - PRESSED: Sunken/indented appearance
 * - BASIN: Extra deep pressed appearance
 */
enum class ShapeType {
    FLAT,
    PRESSED,
    BASIN
}

/**
 * Neumorphic color configuration for soft UI shadows.
 * Uses carefully tuned colors for optimal neumorphic effect.
 */
@Immutable
data class NeumorphicColors(
    val lightShadow: Color,
    val darkShadow: Color,
    val background: Color,
    val onBackground: Color
)

// ============================================================================
// PURE NEUMORPHIC COLOR PALETTES
// These are dedicated neumorphic colors, NOT derived from Material You
// ============================================================================

/**
 * Light theme neumorphic colors - warm soft gray palette
 * Background: #E4E0DA (warm gray)
 * Perfect for the classic neumorphic "soft UI" look
 */
val LightNeumorphicColors = NeumorphicColors(
    lightShadow = Color(0xFFFFFFFF),      // Pure white highlight
    darkShadow = Color(0xFFD6D1CA),        // Softer warm gray shadow (was #BEB9B3)
    background = Color(0xFFE4E0DA),        // Warm soft gray background
    onBackground = Color(0xFF2E2E2E)       // Dark text
)

/**
 * Dark theme neumorphic colors - deep charcoal palette
 * Background: #2E2E2E (charcoal)
 * Subtle shadows for elegant dark neumorphism
 */
val DarkNeumorphicColors = NeumorphicColors(
    lightShadow = Color(0xFF3D3D3D),       // Slightly softer gray highlight
    darkShadow = Color(0xFF1F1F1F),        // Slightly less harsh dark shadow
    background = Color(0xFF2E2E2E),        // Charcoal background
    onBackground = Color(0xFFE4E0DA)       // Light text
)

/**
 * Specialty palette for Favorites - Soft Rosy Red
 */
val FavoriteNeumorphicColors = NeumorphicColors(
    lightShadow = Color(0xFFFFEEEE),       // Very soft light highlight
    darkShadow = Color(0xFFFFBABA),        // Rosy shadow
    background = Color(0xFFFFD6D6),        // Soft rosy background
    onBackground = Color(0xFF8B0000)       // Deep red for contrast
)

/**
 * Get neumorphic colors based on system dark/light mode.
 * Uses PURE neumorphic palettes (not Material You dynamic colors).
 */
@Composable
fun rememberNeumorphicColors(): NeumorphicColors {
    val isDark = isSystemInDarkTheme()
    return remember(isDark) {
        if (isDark) DarkNeumorphicColors else LightNeumorphicColors
    }
}

/**
 * Get light source offsets based on direction.
 */
private fun getLightSourceOffsets(
    lightSource: LightSource,
    offsetPx: Float
): Pair<Offset, Offset> {
    return when (lightSource) {
        LightSource.LEFT_TOP -> Pair(
            Offset(-offsetPx, -offsetPx), // light shadow
            Offset(offsetPx, offsetPx)     // dark shadow
        )
        LightSource.RIGHT_TOP -> Pair(
            Offset(offsetPx, -offsetPx),
            Offset(-offsetPx, offsetPx)
        )
        LightSource.LEFT_BOTTOM -> Pair(
            Offset(-offsetPx, offsetPx),
            Offset(offsetPx, -offsetPx)
        )
        LightSource.RIGHT_BOTTOM -> Pair(
            Offset(offsetPx, offsetPx),
            Offset(-offsetPx, -offsetPx)
        )
    }
}

/**
 * Modifier for creating a neumorphic outer shadow effect (raised/elevated look).
 * 
 * @param lightShadowColor Color for the light shadow
 * @param darkShadowColor Color for the dark shadow
 * @param shadowOffset How far the shadow extends from the element
 * @param shadowRadius Blur radius for the shadow softness
 * @param cornerRadius Corner radius for rounded shapes
 * @param lightSource Direction of the light source
 */
/**
 * Modifier for creating a neumorphic outer shadow effect (raised/elevated look).
 * 
 * @param lightShadowColor Color for the light shadow
 * @param darkShadowColor Color for the dark shadow
 * @param shadowOffset How far the shadow extends from the element
 * @param shadowRadius Blur radius for the shadow softness
 * @param cornerRadius Corner radius for rounded shapes
 * @param lightSource Direction of the light source
 */
fun Modifier.softOuterShadow(
    lightShadowColor: Color,
    darkShadowColor: Color,
    shadowOffset: Dp = 8.dp,
    shadowRadius: Dp = 12.dp,
    cornerRadius: Dp = 16.dp,
    lightSource: LightSource = LightSource.LEFT_TOP
): Modifier = this.drawBehind {
    val offsetPx = shadowOffset.toPx()
    val radiusPx = shadowRadius.toPx()
    val cornerPx = cornerRadius.toPx()
    
    val (lightOffset, darkOffset) = getLightSourceOffsets(lightSource, offsetPx)

    // Dark shadow
    drawIntoCanvas { canvas ->
        val darkPaint = Paint().apply {
            color = darkShadowColor
            asFrameworkPaint().apply {
                isAntiAlias = true
                if (radiusPx > 0) {
                    maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        
        canvas.drawRoundRect(
            left = darkOffset.x,
            top = darkOffset.y,
            right = size.width + darkOffset.x,
            bottom = size.height + darkOffset.y,
            radiusX = cornerPx,
            radiusY = cornerPx,
            paint = darkPaint
        )
    }

    // Light shadow
    drawIntoCanvas { canvas ->
        val lightPaint = Paint().apply {
            color = lightShadowColor
            asFrameworkPaint().apply {
                isAntiAlias = true
                if (radiusPx > 0) {
                    maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        
        canvas.drawRoundRect(
            left = lightOffset.x,
            top = lightOffset.y,
            right = size.width + lightOffset.x,
            bottom = size.height + lightOffset.y,
            radiusX = cornerPx,
            radiusY = cornerPx,
            paint = lightPaint
        )
    }
}

/**
 * Modifier for creating a neumorphic inner shadow effect (pressed/indented look).
 * Improved implementation using path subtraction for high fidelity.
 */
fun Modifier.softInnerShadow(
    lightShadowColor: Color,
    darkShadowColor: Color,
    shadowRadius: Dp = 6.dp,
    cornerRadius: Dp = 8.dp,
    spread: Dp = 2.dp,
    lightSource: LightSource = LightSource.LEFT_TOP
): Modifier = this.drawWithContent {
    drawContent()
    
    val radiusPx = shadowRadius.toPx()
    val cornerPx = cornerRadius.toPx()
    val spreadPx = spread.toPx()

    val (lightOffset, darkOffset) = getLightSourceOffsets(lightSource, spreadPx)

    // Draw Dark Inner Shadow
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            color = darkShadowColor
            asFrameworkPaint().apply {
                isAntiAlias = true
                if (radiusPx > 0) {
                    maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }

        canvas.save()
        // Clip to the component's rounded rect
        val mainPath = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx))
        }
        canvas.clipPath(mainPath)

        // Create a "frame" path: a large rectangle with the component's hole subtracted
        // For the dark shadow, we offset this hole AWAY from the light source
        val framePath = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(Rect(-size.width, -size.height, size.width * 2, size.height * 2))
            addRoundRect(
                RoundRect(
                    left = darkOffset.x,
                    top = darkOffset.y,
                    right = size.width + darkOffset.x,
                    bottom = size.height + darkOffset.y,
                    radiusX = cornerPx,
                    radiusY = cornerPx
                )
            )
        }

        canvas.drawPath(framePath, paint)
        canvas.restore()
    }

    // Draw Light Inner Highlight
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            color = lightShadowColor
            asFrameworkPaint().apply {
                isAntiAlias = true
                if (radiusPx > 0) {
                    maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }

        canvas.save()
        val mainPath = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx))
        }
        canvas.clipPath(mainPath)

        // For the light highlight, we offset the hole TOWARDS the light source
        val framePath = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(Rect(-size.width, -size.height, size.width * 2, size.height * 2))
            addRoundRect(
                RoundRect(
                    left = lightOffset.x,
                    top = lightOffset.y,
                    right = size.width + lightOffset.x,
                    bottom = size.height + lightOffset.y,
                    radiusX = cornerPx,
                    radiusY = cornerPx
                )
            )
        }

        canvas.drawPath(framePath, paint)
        canvas.restore()
    }
}

/**
 * Modifier for creating a basin effect (extra deep pressed appearance).
 */
fun Modifier.basinShadow(
    lightShadowColor: Color,
    darkShadowColor: Color,
    shadowRadius: Dp = 10.dp,
    cornerRadius: Dp = 8.dp,
    spread: Dp = 4.dp,
    lightSource: LightSource = LightSource.LEFT_TOP
): Modifier = this.softInnerShadow(
    lightShadowColor = lightShadowColor,
    darkShadowColor = darkShadowColor,
    shadowRadius = shadowRadius,
    cornerRadius = cornerRadius,
    spread = spread,
    lightSource = lightSource
)

/**
 * Convenience composable modifier for neumorphic outer shadow with automatic colors.
 */
@Composable
fun Modifier.neumorphicRaised(
    shadowOffset: Dp = 8.dp,
    shadowRadius: Dp = 12.dp,
    cornerRadius: Dp = 16.dp,
    lightSource: LightSource = LightSource.LEFT_TOP,
    colors: NeumorphicColors? = null
): Modifier {
    val neumorphicColors = colors ?: rememberNeumorphicColors()
    return this
        .graphicsLayer() // Isolate shadow rendering layer
        .softOuterShadow(
            lightShadowColor = neumorphicColors.lightShadow,
            darkShadowColor = neumorphicColors.darkShadow,
            shadowOffset = shadowOffset,
            shadowRadius = shadowRadius,
            cornerRadius = cornerRadius,
            lightSource = lightSource
        )
}

/**
 * Convenience composable modifier for neumorphic inner shadow with automatic colors.
 */
@Composable
fun Modifier.neumorphicPressed(
    shadowRadius: Dp = 6.dp,
    cornerRadius: Dp = 8.dp,
    spread: Dp = 2.dp,
    lightSource: LightSource = LightSource.LEFT_TOP,
    colors: NeumorphicColors? = null
): Modifier {
    val neumorphicColors = colors ?: rememberNeumorphicColors()
    return this.softInnerShadow(
        lightShadowColor = neumorphicColors.lightShadow,
        darkShadowColor = neumorphicColors.darkShadow,
        shadowRadius = shadowRadius,
        cornerRadius = cornerRadius,
        spread = spread,
        lightSource = lightSource
    )
}

/**
 * Convenience composable modifier for basin effect with automatic colors.
 */
@Composable
fun Modifier.neumorphicBasin(
    shadowRadius: Dp = 10.dp,
    cornerRadius: Dp = 8.dp,
    spread: Dp = 4.dp,
    lightSource: LightSource = LightSource.LEFT_TOP,
    colors: NeumorphicColors? = null
): Modifier {
    val neumorphicColors = colors ?: rememberNeumorphicColors()
    return this.basinShadow(
        lightShadowColor = neumorphicColors.lightShadow,
        darkShadowColor = neumorphicColors.darkShadow,
        shadowRadius = shadowRadius,
        cornerRadius = cornerRadius,
        spread = spread,
        lightSource = lightSource
    )
}

/**
 * Neumorphic background that provides the perfect base color for neumorphic elements.
 */
@Composable
fun Modifier.neumorphicBackground(
    cornerRadius: Dp = 16.dp,
    colors: NeumorphicColors? = null
): Modifier {
    val neumorphicColors = colors ?: rememberNeumorphicColors()
    return this.background(
        color = neumorphicColors.background,
        shape = RoundedCornerShape(cornerRadius)
    )
}

/**
 * Combined neumorphic surface - background + raised shadow for a complete neumorphic element.
 */
@Composable
fun Modifier.neumorphicSurface(
    shadowOffset: Dp = 8.dp,
    shadowRadius: Dp = 12.dp,
    cornerRadius: Dp = 16.dp,
    lightSource: LightSource = LightSource.LEFT_TOP
): Modifier {
    val colors = rememberNeumorphicColors()
    return this
        .graphicsLayer() // Isolate shadow rendering layer
        .softOuterShadow(
            lightShadowColor = colors.lightShadow,
            darkShadowColor = colors.darkShadow,
            shadowOffset = shadowOffset,
            shadowRadius = shadowRadius,
            cornerRadius = cornerRadius,
            lightSource = lightSource
        )
        .background(
            color = colors.background,
            shape = RoundedCornerShape(cornerRadius)
        )
}

/**
 * Generic neumorphic modifier that supports custom shapes.
 */
@Composable
fun Modifier.neumorphic(
    elevation: Dp = 6.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    lightSource: LightSource = LightSource.LEFT_TOP
): Modifier {
    val colors = rememberNeumorphicColors()
    val density = LocalDensity.current
    
    val cornerRadius = remember(shape, density) {
        when (shape) {
            is RoundedCornerShape -> {
                val px = shape.topStart.toPx(androidx.compose.ui.geometry.Size(100f, 100f), density)
                with(density) { px.toDp() }
            }
            else -> 0.dp
        }
    }

    return this
        .softOuterShadow(
            lightShadowColor = colors.lightShadow,
            darkShadowColor = colors.darkShadow,
            shadowOffset = elevation,
            shadowRadius = elevation * 1.5f,
            cornerRadius = cornerRadius,
            lightSource = lightSource
        )
        .background(
            color = colors.background,
            shape = shape
        )
}

/**
 * Neumorphic icon button with press state animation.
 * Shows raised effect when not pressed, pressed effect when clicked.
 */
@Composable
fun NeumorphicIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    lightSource: LightSource = LightSource.LEFT_TOP
) {
    val colors = rememberNeumorphicColors()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val shadowOffset by animateFloatAsState(
        targetValue = if (isPressed) 1f else 3f,
        animationSpec = tween(100),
        label = "shadowOffset"
    )
    
    val cornerRadius = size / 2

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (isPressed) {
                    Modifier.softInnerShadow(
                        lightShadowColor = colors.lightShadow,
                        darkShadowColor = colors.darkShadow,
                        shadowRadius = 3.dp,
                        cornerRadius = cornerRadius,
                        spread = 1.dp,
                        lightSource = lightSource
                    )
                } else {
                    Modifier.softOuterShadow(
                        lightShadowColor = colors.lightShadow,
                        darkShadowColor = colors.darkShadow,
                        shadowOffset = shadowOffset.dp,
                        shadowRadius = 5.dp,
                        cornerRadius = cornerRadius,
                        lightSource = lightSource
                    )
                }
            )
            .clip(CircleShape)
            .background(colors.background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

/**
 * Neumorphic toggle button that keeps raised effect but shows active state via icon color.
 */
@Composable
fun NeumorphicToggleButton(
    isActive: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    inactiveTint: Color = LocalContentColor.current,
    lightSource: LightSource = LightSource.LEFT_TOP
) {
    val colors = rememberNeumorphicColors()
    val cornerRadius = size / 2
    
    // Animate shadow depth - slightly less prominent when active
    val shadowOffset by animateFloatAsState(
        targetValue = if (isActive) 2f else 3f,
        animationSpec = tween(150),
        label = "toggleShadow"
    )

    Box(
        modifier = modifier
            .size(size)
            .softOuterShadow(
                lightShadowColor = colors.lightShadow,
                darkShadowColor = colors.darkShadow,
                shadowOffset = shadowOffset.dp,
                shadowRadius = if (isActive) 4.dp else 5.dp,
                cornerRadius = cornerRadius,
                lightSource = lightSource
            )
            .clip(CircleShape)
            .background(colors.background)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (isActive) activeTint else inactiveTint.copy(alpha = if (enabled) 0.5f else 0.38f)
        )
    }
}

/**
 * Neumorphic play/pause button - Large central button for media controls.
 * Shows play, pause, or replay icons based on state with press animation.
 */
@Composable
fun NeumorphicPlayButton(
    isPlaying: Boolean,
    isEnded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 32.dp,
    lightSource: LightSource = LightSource.LEFT_TOP
) {
    val colors = rememberNeumorphicColors()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val shadowOffset by animateFloatAsState(
        targetValue = if (isPressed) 1f else 4f,
        animationSpec = tween(100),
        label = "shadowOffset"
    )
    
    val cornerRadius = size / 2
    
    // Determine icon based on state
    val icon = when {
        isPlaying -> Icons.Filled.Pause
        isEnded -> Icons.Filled.Replay
        else -> Icons.Filled.PlayArrow
    }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (isPressed) {
                    Modifier.softInnerShadow(
                        lightShadowColor = colors.lightShadow,
                        darkShadowColor = colors.darkShadow,
                        shadowRadius = 4.dp,
                        cornerRadius = cornerRadius,
                        spread = 2.dp,
                        lightSource = lightSource
                    )
                } else {
                    Modifier.softOuterShadow(
                        lightShadowColor = colors.lightShadow,
                        darkShadowColor = colors.darkShadow,
                        shadowOffset = shadowOffset.dp,
                        shadowRadius = 7.dp,
                        cornerRadius = cornerRadius,
                        lightSource = lightSource
                    )
                }
            )
            .clip(CircleShape)
            .background(colors.background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(iconSize),
            tint = colors.onBackground
        )
    }
}
