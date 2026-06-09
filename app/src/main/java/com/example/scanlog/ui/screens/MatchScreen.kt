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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.RfidRange
import com.example.scanlog.rfid.RfidController
import com.example.scanlog.ui.viewmodel.MatchState
import com.example.scanlog.ui.viewmodel.MatchViewModel
import com.example.scanlog.ui.viewmodel.SettingsViewModel
import com.example.scanlog.util.BldScanner
import com.example.scanlog.util.ScannerConstants
import kotlinx.coroutines.delay

private val colorMatch = Color(0xFF00C853)
private val colorMismatch = Color(0xFFD50000)

@Composable
fun MatchScreen(
    vm: MatchViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by vm.state.collectAsState()
    val rfidRange by settingsVm.rfidRange.collectAsState()
    val snackbarMessage by vm.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Compare mode is single-fire: one barcode decode per trigger press, so each
    // RFID tag lines up one-to-one with the barcode it's verified against. Restore
    // continuous sweep (the Scan-tab default for RFID+Barcode) when leaving.
    DisposableEffect(Unit) {
        BldScanner.setContinuous(false)
        onDispose { BldScanner.setContinuous(true) }
    }

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

    // Auto-reset + feedback when result arrives
    LaunchedEffect(state) {
        val result = state as? MatchState.Result ?: return@LaunchedEffect
        if (result.matched) {
            vm.playBeep(volume = 1.0f, rate = 1.15f)
            mainHandler.post { doublePulseVibrate() }
        }
        delay(1000)
        vm.reset()
    }

    // Snackbar for unknown RFID tag
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearSnackbar()
    }

    // RFID tags — come from the GClient SDK, not broadcasts.
    LaunchedEffect(Unit) {
        RfidController.tagFlow.collect { epc -> vm.onRfidScanned(epc) }
    }

    // Barcode receiver
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val raw: String = when (intent.action) {
                    ScannerConstants.ACTION_DECODE_XCHENG ->
                        intent.getStringExtra(ScannerConstants.EXTRA_DECODE_XCHENG)
                    ScannerConstants.ACTION_DECODE_BLD -> {
                        val bytes = intent.getByteArrayExtra(ScannerConstants.EXTRA_DECODE_BLD_BYTES)
                        val len = intent.getIntExtra(ScannerConstants.EXTRA_DECODE_BLD_LEN, bytes?.size ?: 0)
                        if (bytes == null || len <= 0) null
                        else String(bytes, 0, len.coerceAtMost(bytes.size))
                    }
                    else -> null
                } ?: return
                vm.onBarcodeScanned(raw)
            }
        }
        val filter = IntentFilter().apply {
            ScannerConstants.ALL_DECODE_ACTIONS.forEach { addAction(it) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        onDispose {
            try { appContext.unregisterReceiver(receiver) }
            catch (_: IllegalArgumentException) { }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is MatchState.Idle -> {
                    Text(
                        text = stringResource(R.string.match_scan_rfid),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                }

                is MatchState.RfidScanned -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = s.label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = s.tagId,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.match_scan_barcode),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is MatchState.Result -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = s.rfidLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.match_expected, s.expectedBarcode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.match_scanned, s.scannedBarcode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Result overlay: border + fill + big symbol
            if (state is MatchState.Result) {
                val matched = (state as MatchState.Result).matched
                val overlayColor = if (matched) colorMatch else colorMismatch
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(20.dp, overlayColor)
                        .background(overlayColor.copy(alpha = 0.26f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (matched) "✓" else "✗",
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Bold,
                        color = overlayColor
                    )
                }
            }

            // RFID range toggle — kept on top so it stays usable during the
            // result flash. Writes through to the reader + persisted setting.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RfidRange.entries.forEach { range ->
                    FilterChip(
                        selected = rfidRange == range,
                        onClick = { settingsVm.setRfidRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
        }
    }
}
