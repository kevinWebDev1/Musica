package com.github.musicyou.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import com.github.musicyou.enums.NavigationLabelsVisibility
import com.github.musicyou.ui.navigation.TopDestinations
import com.github.musicyou.utils.homeScreenTabIndexKey
import com.github.musicyou.utils.navigationLabelsVisibilityKey
import com.github.musicyou.utils.rememberPreference

@Composable
fun BottomNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    var navigationLabelsVisibility by rememberPreference(
        navigationLabelsVisibilityKey,
        NavigationLabelsVisibility.Visible
    )
    val (_, onScreenChanged) = rememberPreference(
        homeScreenTabIndexKey,
        defaultValue = 0
    )

    Box(
        contentAlignment = Alignment.BottomCenter
    ) {
        NavigationBar(
            modifier = if (navigationLabelsVisibility == NavigationLabelsVisibility.Hidden) {
                Modifier.heightIn(max = 90.dp)
            } else Modifier
        ) {
            TopDestinations.list.forEachIndexed { index, destination ->
                val selected =
                    currentDestination?.hierarchy?.any { it.hasRoute(route = destination.route::class) } == true

                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            onScreenChanged(index)
                            navController.navigate(route = destination.route) {
                                popUpTo(id = navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        }
                    },
                    icon = {
                        if (destination.route == com.github.musicyou.ui.navigation.Routes.Videos) {
                            // Premium Ring for Video Tab
                            val ringModifier = if (selected) {
                                Modifier
                                    .size(42.dp)
                                    .border(
                                        width = 2.dp,
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        ),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .padding(8.dp)
                            } else {
                                Modifier.padding(8.dp)
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = ringModifier
                            ) {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(id = destination.resourceId)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = stringResource(id = destination.resourceId)
                            )
                        }
                    },
                    label = {
                        if (navigationLabelsVisibility != NavigationLabelsVisibility.Hidden) {
                            Text(
                                text = stringResource(id = destination.resourceId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    alwaysShowLabel = navigationLabelsVisibility != NavigationLabelsVisibility.VisibleWhenActive,
                    colors = if (destination.route == com.github.musicyou.ui.navigation.Routes.Videos) {
                        NavigationBarItemDefaults.colors(
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    } else {
                        NavigationBarItemDefaults.colors()
                    }
                )
            }
        }
    }
}