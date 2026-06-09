package com.example.scanlog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.example.scanlog.ui.screens.MatchScreen
import com.example.scanlog.ui.screens.ScanScreen
import com.example.scanlog.ui.screens.SettingsScreen
import com.example.scanlog.ui.viewmodel.ScanViewModel
import com.example.scanlog.ui.viewmodel.SettingsViewModel
import com.example.scanlog.data.ScanMode
import com.example.scanlog.rfid.RfidController

private sealed class Tab(val route: String, @StringRes val labelRes: Int) {
    data object Scan : Tab("scan", R.string.tab_scan)
    data object Counts : Tab("counts", R.string.tab_counts)
    data object History : Tab("history", R.string.tab_history)
    data object Match : Tab("match", R.string.tab_match)
    data object Settings : Tab("settings", R.string.tab_settings)
}

@Composable
fun AppNav() {
    val navController = rememberNavController()

    // One shared ScanViewModel for entire app
    val scanVM: ScanViewModel = viewModel()
    val settingsVM: SettingsViewModel = viewModel()
    val scanMode by settingsVM.scanMode.collectAsState()

    // Match tab only appears in RFID+Barcode mode.
    val tabs = remember(scanMode) {
        if (scanMode == ScanMode.RFID_AND_BARCODE) {
            listOf(Tab.Scan, Tab.Counts, Tab.History, Tab.Match, Tab.Settings)
        } else {
            listOf(Tab.Scan, Tab.Counts, Tab.History, Tab.Settings)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    // Enable barcode logging only on Scan tab.
    LaunchedEffect(currentRoute) {
        scanVM.setScanEnabled(currentRoute == Tab.Scan.route)
    }

    // RFID gate: open only when (mode == RFID+Barcode) AND user is on a screen that uses RFID.
    // Inventory itself no longer auto-starts — it fires on hardware trigger keypress
    // (see MainActivity.dispatchKeyEvent).
    LaunchedEffect(currentRoute, scanMode) {
        val usesRfid = scanMode == ScanMode.RFID_AND_BARCODE &&
                (currentRoute == Tab.Scan.route || currentRoute == Tab.Match.route)
        RfidController.setGate(usesRfid)
    }

    // If user switches to barcode-only while on Match tab, bounce them to Scan.
    LaunchedEffect(scanMode) {
        if (scanMode == ScanMode.BARCODE_ONLY && currentRoute == Tab.Match.route) {
            navController.navigate(Tab.Scan.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

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
                        icon = {}
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Tab.Scan.route
            ) {
                composable(Tab.Scan.route) {
                    ScanScreen(vm = scanVM)
                }
                composable(Tab.Counts.route) {
                    CountsScreen()
                }
                composable(Tab.History.route) {
                    HistoryScreen(
                        onOpenDay = { day ->
                            navController.navigate("day/$day")
                        }
                    )
                }
                composable(Tab.Match.route) {
                    MatchScreen(settingsVm = settingsVM)
                }
                composable(Tab.Settings.route) {
                    SettingsScreen()
                }
                composable(
                    route = "day/{day}",
                    arguments = listOf(navArgument("day") { type = NavType.StringType })
                ) { entry ->
                    val day = entry.arguments?.getString("day") ?: return@composable
                    DayDetailScreen(
                        day = day,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
