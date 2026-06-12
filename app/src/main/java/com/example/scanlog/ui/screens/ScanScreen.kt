package com.example.scanlog.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.example.scanlog.data.RfidBarcodeCatalog
import com.example.scanlog.data.ScanMode
import com.example.scanlog.rfid.RfidController
import com.example.scanlog.ui.viewmodel.ScanViewModel
import com.example.scanlog.ui.viewmodel.SettingsViewModel
import com.example.scanlog.util.ScannerConstants
import kotlinx.coroutines.delay
import java.time.LocalDate



@Composable
fun ScanScreen(
    vm: ScanViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val catalog = remember(appContext) { BarcodeCatalog(appContext) }
    val rfidCatalog = remember(appContext) { RfidBarcodeCatalog(appContext) }

    val scanMode by settingsVm.scanMode.collectAsState()
    val holdCount by RfidController.holdCount.collectAsState()
    val lastRssi by RfidController.lastRssi.collectAsState()

    val recentEvents by vm.recentEvents.collectAsState()
    val todayCounts by vm.todayCounts.collectAsState()

    val today = remember { LocalDate.now().toString() }
    val total = remember(todayCounts) { todayCounts.values.sum() }

    var showUndoConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    var flashOn by remember { mutableStateOf(false) }
    var flashToken by remember { mutableIntStateOf(0) }
    var failToken by remember { mutableIntStateOf(0) }

    fun triggerFail() { failToken++ }
    fun triggerSuccess() { flashToken++ }

    // Main thread handler (safe for BroadcastReceiver callback)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun doublePulseVibrate() {
        val vibrator = runCatching {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }.getOrNull() ?: return

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= 26) {
            val timings = longArrayOf(0L, 70L, 40L, 150L)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 70, 40, 150), -1)
        }
    }




    // Flash runner
    LaunchedEffect(flashToken) {
        if (flashToken == 0) return@LaunchedEffect
        flashOn = true
        delay(250)
        flashOn = false
    }

    // Snackbar runner with rate limit. During a Strong-mode sweep multiple
    // unknown EPCs can hit in rapid succession; without this we'd queue one
    // toast per tag and spam the UI for many seconds.
    var lastFailShownMs by remember { mutableStateOf(0L) }
    LaunchedEffect(failToken) {
        if (failToken == 0) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastFailShownMs < 3000L) return@LaunchedEffect
        lastFailShownMs = now
        snackbarHostState.showSnackbar(message = context.getString(R.string.scan_not_logged))
    }

    fun feedback(ok: Boolean) {
        mainHandler.post {
            if (ok) {
                vm.playBeep(volume = 1.0f, rate = 1.15f)
                doublePulseVibrate()
                triggerSuccess()
            } else {
                triggerFail()
            }
        }
    }

    // Barcode path — raw code stored as-is (may include known SKUs from catalog).
    fun handleScanResult(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        vm.record(trimmed) { ok -> feedback(ok) }
    }

    // RFID path — unique EPC rolled up under its category code. Unknown EPCs
    // (not in rfid_barcode_map.csv) get the fail snackbar. Already-counted EPCs
    // (in today's seenEpcs) are a silent no-op so re-sweeps don't spam toasts.
    fun handleRfidTag(epc: String) {
        val trimmed = epc.trim()
        if (trimmed.isEmpty()) return
        val entry = rfidCatalog.lookup(trimmed)
        if (entry == null) {
            feedback(false)
            return
        }
        vm.recordRfidTag(trimmed, entry.barcode) { ok ->
            if (ok) feedback(true)
            // else: known tag, already counted today — silently ignore.
        }
    }

    // Barcode receiver — only active in BARCODE_ONLY mode. In RFID+Barcode mode
    // the Scan tab is pure RFID counting; stray barcode trigger presses are ignored
    // so a misfire can't inflate counts with an unrelated code.
    DisposableEffect(appContext, scanMode) {
        if (scanMode != ScanMode.BARCODE_ONLY) {
            onDispose { }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    intent ?: return
                    val raw: String = when (intent.action) {
                        // Xcheng: single String extra.
                        ScannerConstants.ACTION_DECODE_XCHENG ->
                            intent.getStringExtra(ScannerConstants.EXTRA_DECODE_XCHENG)
                        // 条捷印 T02: byte[] payload + explicit length (extra null bytes
                        // past `length` if we don't trim).
                        ScannerConstants.ACTION_DECODE_BLD -> {
                            val bytes = intent.getByteArrayExtra(ScannerConstants.EXTRA_DECODE_BLD_BYTES)
                            val len = intent.getIntExtra(ScannerConstants.EXTRA_DECODE_BLD_LEN, bytes?.size ?: 0)
                            if (bytes == null || len <= 0) null
                            else String(bytes, 0, len.coerceAtMost(bytes.size))
                        }
                        else -> null
                    } ?: return
                    handleScanResult(raw)
                }
            }
            val filter = IntentFilter().apply {
                ScannerConstants.ALL_DECODE_ACTIONS.forEach { addAction(it) }
            }
            // The barcode broadcast comes from the PDA's scanner service (a SEPARATE
            // app). On Android 13+ (API 33) a runtime receiver must be EXPORTED to
            // receive broadcasts from other apps — RECEIVER_NOT_EXPORTED would silently
            // drop them, breaking barcode scanning.
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            onDispose {
                try { appContext.unregisterReceiver(receiver) }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    // RFID tags — come from the GClient SDK, not broadcasts. Rolled up by category.
    LaunchedEffect(Unit) {
        RfidController.tagFlow.collect { epc -> handleRfidTag(epc) }
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

                // Live "+N" badge for the active trigger-hold (RFID mode only).
                if (scanMode == ScanMode.RFID_AND_BARCODE && holdCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "+$holdCount",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF00C853)
                    )
                }

                // Live last-read RSSI — calibrate the Settings RSSI floor against this.
                if (scanMode == ScanMode.RFID_AND_BARCODE && lastRssi != 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.scan_last_rssi, lastRssi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // BIG full-screen flash overlay
            if (flashOn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(20.dp, Color(0xFF00C853))
                        .background(Color(0x4400C853))
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
