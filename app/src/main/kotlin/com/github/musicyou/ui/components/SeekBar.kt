package com.github.musicyou.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.musicyou.ui.styling.neumorphicPressed
import com.github.musicyou.ui.styling.neumorphicRaised
import com.github.musicyou.ui.styling.rememberNeumorphicColors
import kotlin.math.roundToLong

@Composable
fun SeekBar(
    value: Long,
    minimumValue: Long,
    maximumValue: Long,
    onDragStart: (Long) -> Unit,
    onDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 10.dp, // Increased default height for deeper effect
    shape: Shape = CircleShape,
    drawSteps: Boolean = false,
) {
    val neumorphicColors = rememberNeumorphicColors()
    var isDragging by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight * 4) // Ample room for interaction and shadows
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(minimumValue, maximumValue) {
                if (maximumValue <= minimumValue) return@pointerInput

                var acc = 0f

                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val newValue = (offset.x / width * (maximumValue - minimumValue) + minimumValue).roundToLong()
                        onDragStart(newValue)
                    },
                    onHorizontalDrag = { _, delta ->
                        if (width > 0) {
                            acc += delta / width * (maximumValue - minimumValue)
                            if (java.lang.Math.abs(acc) >= 1f) {
                                onDrag(acc.toLong())
                                acc -= acc.toLong()
                            }
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        acc = 0f
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        acc = 0f
                        onDragEnd()
                    }
                )
            }
            .pointerInput(minimumValue, maximumValue) {
                if (maximumValue <= minimumValue) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        val newValue = (offset.x / width * (maximumValue - minimumValue) + minimumValue).roundToLong()
                        onDragStart(newValue)
                        onDragEnd()
                    }
                )
            }
    ) {
        val progress = if (maximumValue > minimumValue) {
            (value.toFloat() - minimumValue) / (maximumValue - minimumValue)
        } else {
            0f
        }

        // Track Background (Sunken "Deep Pressed" look)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.Center)
                .neumorphicPressed(
                    cornerRadius = barHeight / 2,
                    shadowRadius = 8.dp,
                    spread = 3.dp
                )
                .background(neumorphicColors.background, CircleShape)
        )

        // Progress Bar (Filled part inside the sunken track)
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(barHeight)
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .background(color)
        )
    }
}
