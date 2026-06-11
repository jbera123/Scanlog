package com.example.scanlog

import android.app.Application
import com.example.scanlog.data.ScanMode
import com.example.scanlog.data.ScanStore
import com.example.scanlog.rfid.RfidController
import com.example.scanlog.util.BldScanner
import com.example.scanlog.util.CountExporter
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScanlogApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun autoExportPastDays(store: ScanStore) {
        val today = LocalDate.now().toString()
        val all = store.snapshotAllDays()
        val exported = store.getExportedDays()
        all.forEach { (day, counts) ->
            if (day != today && day !in exported && counts.isNotEmpty()) {
                if (CountExporter.exportDay(applicationContext, day, counts)) {
                    store.markExported(day)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Wake the 条捷印 T02 BLD scanner into broadcast mode. No-op on other PDAs.
        BldScanner.init(this)

        // Open the UHF reader on a background thread so app startup isn't blocked
        // by the serial port handshake (~100 ms + 300 ms power-on sleep).
        appScope.launch {
            val store = ScanStore(applicationContext)

            // Apply the persisted scan mode to the laser's continuous setting
            // before the user even touches the trigger.
            val mode = store.scanMode.first()
            BldScanner.setContinuous(mode == ScanMode.RFID_AND_BARCODE)

            // Cache the persisted range (power) so it's applied when the reader is
            // first opened. This only sets a field — it does not open the reader.
            RfidController.setRange(store.rfidRange.first())

            // NOTE: the UHF reader is deliberately NOT opened here. It is opened
            // lazily by AppNav only when the user is in RFID_AND_BARCODE mode, so
            // the default barcode-only app never powers on (or can be destabilized
            // by) the reader. See AppNav's scanMode effect.

            // Daily auto-export: any past-day's counts that haven't been written
            // to Downloads yet get exported now. Today's data is left alone.
            runCatching { autoExportPastDays(store) }
                .onFailure { android.util.Log.w("ScanlogApp", "auto-export error: ${it.message}") }

            // Drop past days' seenEpcs (dedup lists) so days_json doesn't grow
            // unbounded — keeps per-scan parse/write cost flat. Counts untouched.
            runCatching { store.pruneOldSeenEpcs() }
                .onFailure { android.util.Log.w("ScanlogApp", "prune error: ${it.message}") }
        }
    }
}
