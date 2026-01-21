package com.example.scanlog.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.BarcodeCatalog
import com.example.scanlog.ui.viewmodel.ScanViewModel
import com.example.scanlog.util.ScannerConstants
import java.time.LocalDate
import com.example.scanlog.ui.components.BarcodeRow


@Composable
fun ScanScreen(
    onOpenHistory: () -> Unit,
    vm: ScanViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val catalog = remember(appContext) { BarcodeCatalog(appContext) }

    val counts by vm.todayCounts.collectAsState()
    var showUndoConfirm by remember { mutableStateOf(false) }

    val today = LocalDate.now().toString()

    // Register scanner broadcast receiver while this screen is in composition.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ScannerConstants.ACTION_DECODE) return
                val raw = intent.getStringExtra(ScannerConstants.EXTRA_DECODE_DATA) ?: return
                val code = raw.trim()
                if (code.isNotEmpty()) vm.record(code)
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
                // Receiver was already unregistered; ignore.
            }
        }
    }

    val sorted = remember(counts) {
        counts.toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
    val total = sorted.sumOf { it.second }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_history))
            }
            OutlinedButton(
                onClick = { showUndoConfirm = true },
                modifier = Modifier.weight(1f),
                enabled = total > 0
            ) {
                Text(stringResource(R.string.undo))
            }
        }

        Spacer(Modifier.height(16.dp))

        if (sorted.isEmpty()) {
            Text(stringResource(R.string.no_scans_yet), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.scanner_mode_broadcast),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            sorted.forEach { (code, count) ->
                BarcodeRow(
                    leftText = catalog.displayText(code),
                    rightText = count.toString()
                )
                HorizontalDivider()
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
