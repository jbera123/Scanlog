package com.example.scanlog.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.BarcodeCatalog
import com.example.scanlog.ui.viewmodel.ScanEvent
import com.example.scanlog.ui.viewmodel.ScanViewModel
import com.example.scanlog.util.BeepPlayer
import com.example.scanlog.util.ScannerConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ScanScreen(
    vm: ScanViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val catalog = remember(appContext) { BarcodeCatalog(appContext) }
    val beepPlayer = remember { BeepPlayer() }

    val counts by vm.todayCounts.collectAsState()
    var showUndoConfirm by remember { mutableStateOf(false) }

    val today = LocalDate.now().toString()

    // Border flash state (alpha anim)
    val flashAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var flashJob by remember { mutableStateOf<Job?>(null) }

    fun triggerFlash() {
        flashJob?.cancel()
        flashJob = scope.launch {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 180))
        }
    }

    // Receiver
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ScannerConstants.ACTION_DECODE) return
                val raw = intent.getStringExtra(ScannerConstants.EXTRA_DECODE_DATA) ?: return
                vm.onScannerInput(raw)
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
            } catch (_: IllegalArgumentException) {
                // ignore
            }
        }
    }

    // React to scan events: success beep+flash; failure toast only
    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is ScanEvent.Success -> {
                    beepPlayer.playSuccess()
                    triggerFlash()
                }
                is ScanEvent.NotLogged -> {
                    // no beep, no flash
                    Toast.makeText(
                        context,
                        context.getString(R.string.scan_not_logged),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val sorted = remember(counts) {
        counts.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
    val total = sorted.sumOf { it.second }

    // Border color: green flash overlay
    val borderColor = Color(0f, 0.8f, 0.2f, flashAlpha.value)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .border(width = 3.dp, color = borderColor)
            .padding(12.dp)
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

        Spacer(Modifier.height(12.dp))

        // Keep your current layout: list + undo confirm dialog
        if (sorted.isEmpty()) {
            Text(stringResource(R.string.no_scans_yet), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.scanner_mode_broadcast), style = MaterialTheme.typography.bodyMedium)
        } else {
            sorted.forEach { (code, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(catalog.displayText(code), style = MaterialTheme.typography.bodyLarge)
                    Text(count.toString(), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = { showUndoConfirm = true },
            enabled = total > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.undo))
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
