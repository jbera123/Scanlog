package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.R
import com.example.scanlog.data.ScanEvent
import com.example.scanlog.data.ScanStore
import com.example.scanlog.util.SimpleBeep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)
    private val beep = SimpleBeep(app.applicationContext, R.raw.beep)



    // Gate: only record scans when Scan tab is active
    private val _scanEnabled = MutableStateFlow(true)
    val scanEnabled: StateFlow<Boolean> = _scanEnabled.asStateFlow()

    fun setScanEnabled(enabled: Boolean) {
        _scanEnabled.value = enabled
    }

    // Today counts from persistent store
    val todayCounts: StateFlow<Map<String, Int>> =
        store.todayCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // In-memory mirror of today's seenEpcs. Read every RFID scan first; if the
    // EPC is already in here we skip the DataStore round-trip and answer "dup"
    // instantly. Backed by a StateFlow so it picks up persisted EPCs at startup
    // and stays in sync if anything mutates the store from elsewhere.
    private val seenEpcCache: StateFlow<Set<String>> =
        store.todaySeenEpcs.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // Testing toggle: when true, the same EPC counts every time (per-day dedup off).
    private val allowRepeatedScans: StateFlow<Boolean> =
        store.allowRepeatedScans.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Recent scan events (in-memory)
    private val _recentEvents = MutableStateFlow<List<ScanEvent>>(emptyList())
    val recentEvents: StateFlow<List<ScanEvent>> = _recentEvents.asStateFlow()

    fun playBeep(volume: Float = 1.0f, rate: Float = 1.15f) {
        beep.play(volume = volume, rate = rate)
    }

    fun record(codeRaw: String, onResult: (Boolean) -> Unit = {}) {
        val code = codeRaw.trim()
        if (code.isEmpty() || !_scanEnabled.value) {
            onResult(false)
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val countAfter = store.recordScan(code, now)

            if (countAfter == null) {
                onResult(false)
                return@launch
            }

            val ev = ScanEvent(
                code = code,
                countAfter = countAfter,
                tsMs = now
            )

            _recentEvents.value = listOf(ev) + _recentEvents.value.take(49)
            onResult(true)
        }
    }

    /**
     * Records an RFID tag scan, rolling it up under its category code (e.g.
     * scanning EPC "AA000007" rolls into the "SEAT4-1-A" bucket). Each unique
     * EPC counts at most once per day.
     */
    fun recordRfidTag(epc: String, rollupCode: String, onResult: (Boolean) -> Unit = {}) {
        val epcTrim = epc.trim().uppercase()
        val rollup = rollupCode.trim().uppercase()
        if (epcTrim.isEmpty() || rollup.isEmpty() || !_scanEnabled.value) {
            onResult(false)
            return
        }

        val repeat = allowRepeatedScans.value

        // Fast path: in-memory dedup. Avoids a DataStore edit on every re-emit
        // of an already-counted tag (which the per-EPC throttle still lets
        // through once per ~1s for tags sitting in the field). Skipped when the
        // repeated-scans testing toggle is on.
        if (!repeat && epcTrim in seenEpcCache.value) {
            onResult(false)
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val countAfter = store.recordRfidTag(epcTrim, rollup, now, allowRepeat = repeat)

            if (countAfter == null) {
                // Race: another caller persisted it between the cache check and
                // here. Treat as duplicate (silent at the UI layer).
                onResult(false)
                return@launch
            }

            val ev = ScanEvent(code = rollup, countAfter = countAfter, tsMs = now)
            _recentEvents.value = listOf(ev) + _recentEvents.value.take(49)
            onResult(true)
        }
    }

    fun undo() {
        viewModelScope.launch {
            store.undoLast()
        }
    }

    override fun onCleared() {
        super.onCleared()
        beep.release()
    }
}
