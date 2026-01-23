package com.example.scanlog.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.BarcodeCatalog
import com.example.scanlog.ui.viewmodel.ScanViewModel
import com.example.scanlog.util.ScannerConstants
import com.example.scanlog.util.SimpleBeep
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun ScanScreen(
    vm: ScanViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val catalog = remember(appContext) { BarcodeCatalog(appContext) }

    val recentEvents by vm.recentEvents.collectAsState()
    val todayCounts by vm.todayCounts.collectAsState()

    val today = remember { LocalDate.now().toString() }
    val total = remember(todayCounts) { todayCounts.values.sum() }

    var showUndoConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Flash triggers
    var flashOn by remember { mutableStateOf(false) }
    var flashToken by remember { mutableIntStateOf(0) }

    // Fail triggers
    var failToken by remember { mutableIntStateOf(0) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // IMPORTANT: your SimpleBeep requires context (per your compiler error)
    val beep = remember(appContext) { SimpleBeep(appContext) }

    fun triggerFail() { failToken++ }
    fun triggerSuccess() { flashToken++ }

    LaunchedEffect(flashToken) {
        if (flashToken == 0) return@LaunchedEffect
        flashOn = true
        delay(140)
        flashOn = false
    }

    LaunchedEffect(failToken) {
        if (failToken == 0) return@LaunchedEffect
        snackbarHostState.showSnackbar(message = context.getString(R.string.scan_not_logged))
    }

    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ScannerConstants.ACTION_DECODE) return
                val raw = intent.getStringExtra(ScannerConstants.EXTRA_DECODE_DATA) ?: return
                val code = raw.trim()
                if (code.isEmpty()) return

                vm.record(code) { ok ->
                    mainHandler.post {
                        if (ok) {
                            beep.play()
                            triggerSuccess()
                        } else {
                            triggerFail()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ScannerConstants.ACTION_DECODE)

        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                @Suppress("DEPRECATION")
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) { }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = stringResource(R.string.today, today),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.total, total),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (recentEvents.isEmpty()) {
                    Text(stringResource(R.string.no_scans_yet), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.scanner_mode_broadcast), style = MaterialTheme.typography.bodyMedium)
                } else {
                    recentEvents.take(10).forEach { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // No weight() -> no internal weight error
                            Text(
                                text = catalog.displayText(e.code),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.78f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = e.countAfter.toString(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        HorizontalDivider()
                    }
                }

                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    onClick = { showUndoConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = total > 0
                ) {
                    Text(stringResource(R.string.undo))
                }
            }

            // BIG full-screen flash
            if (flashOn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(12.dp, Color(0xFF00C853))
                        .background(Color(0x2200C853))
                )
            }
        }
    }

    if (showUndoConfirm) {
        AlertDialog(
            onDismissRequest = { showUndoConfirm = false },
            title = { Text(stringResource(R.string.undo_confirm_title)) },
            text = { Text(stringResource(R.string.undo_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    showUndoConfirm = false
                    vm.undo()
                }) { Text(stringResource(R.string.undo)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUndoConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
