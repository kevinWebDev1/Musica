package com.github.musicyou.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.ui.navigation.Routes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScaffold(
    navController: NavController,
    sheetState: SheetState,
    scaffoldPadding: PaddingValues,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    
    // Check if we're on the FullscreenPlayer route
    val currentRoute = navController.currentBackStackEntry?.destination?.route
    val isOnFullscreenPlayer = currentRoute == "com.github.musicyou.ui.navigation.Routes.FullscreenPlayer"

    Box(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets(
                left = scaffoldPadding.calculateLeftPadding(layoutDirection),
                right = scaffoldPadding.calculateRightPadding(layoutDirection)
            )
        )
    ) {
        if (isOnFullscreenPlayer) {
            // When on FullscreenPlayer, don't show the BottomSheet at all
            Surface(
                color = MaterialTheme.colorScheme.background,
                content = content
            )
        } else {
            BottomSheetScaffold(
                sheetContent = {
                    Box(
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        MiniPlayer(
                            openPlayer = {
                                navController.navigate(Routes.FullscreenPlayer)
                            },
                            stopPlayer = {
                                scope.launch { sheetState.hide() }
                            }
                        )
                    }
                },
                scaffoldState = scaffoldState,
                sheetPeekHeight = 76.dp + 32.dp + scaffoldPadding.calculateBottomPadding(),
                sheetMaxWidth = Int.MAX_VALUE.dp,
                sheetDragHandle = {
                    Surface(
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
                    }
                }
            ) {
                val bottomPadding = animateDpAsState(
                    targetValue = if (sheetState.currentValue == SheetValue.Hidden) scaffoldPadding.calculateBottomPadding() else scaffoldPadding.calculateBottomPadding() + 76.dp + 32.dp,
                    label = "padding"
                )

                CompositionLocalProvider(value = LocalPlayerPadding provides bottomPadding.value) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        content = content
                    )
                }
            }
        }
    }
}