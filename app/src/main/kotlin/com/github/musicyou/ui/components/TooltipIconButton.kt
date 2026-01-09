package com.github.musicyou.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Icon button with tooltip showing on long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    @StringRes description: Int,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    inTopBar: Boolean = false
) {
    val tooltipState = rememberTooltipState()
    
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(text = stringResource(id = description))
            }
        },
        state = tooltipState
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(id = description),
                modifier = Modifier.size(if (inTopBar) 24.dp else 24.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.38f)
            )
        }
    }
}
