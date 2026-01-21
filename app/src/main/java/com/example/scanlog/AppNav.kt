package com.example.scanlog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.scanlog.ui.screens.CountsScreen
import com.example.scanlog.ui.screens.DayDetailScreen
import com.example.scanlog.ui.screens.HistoryScreen
import com.example.scanlog.ui.screens.ScanScreen
import com.example.scanlog.ui.screens.SettingsScreen

private sealed class Tab(val route: String, @param:StringRes val labelRes: Int) {
    data object Scan : Tab("scan", R.string.tab_scan)
    data object Counts : Tab("counts", R.string.tab_counts)
    data object History : Tab("history", R.string.tab_history)
    data object Settings : Tab("settings", R.string.tab_settings)
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val tabs = listOf(Tab.Scan, Tab.Counts, Tab.History, Tab.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                        icon = { /* optional */ }
                    )
                }
            }
        }
    ) { scaffoldPadding ->
        Box(Modifier.padding(scaffoldPadding)) {
            NavHost(
                navController = navController,
                startDestination = Tab.Scan.route
            ) {
                composable(Tab.Scan.route) {
                    ScanScreen(
                        onOpenHistory = { navController.navigate(Tab.History.route) }
                    )
                }
                composable(Tab.Counts.route) { CountsScreen() }
                composable(Tab.History.route) {
                    HistoryScreen(
                        onOpenDay = { day -> navController.navigate("day/$day") }
                    )
                }
                composable(Tab.Settings.route) { SettingsScreen() }

                composable(
                    route = "day/{day}",
                    arguments = listOf(navArgument("day") { type = NavType.StringType })
                ) { backStackEntry ->
                    val day = backStackEntry.arguments?.getString("day") ?: return@composable
                    DayDetailScreen(
                        day = day,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
