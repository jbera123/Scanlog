package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanEvent
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)

    // Gate: only record scans when Scan tab is active
    private val _scanEnabled = MutableStateFlow(true)
    val scanEnabled: StateFlow<Boolean> = _scanEnabled.asStateFlow()

    fun setScanEnabled(enabled: Boolean) {
        _scanEnabled.value = enabled
    }

    // Today counts from persistent store
    val todayCounts: StateFlow<Map<String, Int>> =
        store.todayCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Recent scan events shown in ScanScreen (most recent first).
    private val _recentEvents = MutableStateFlow<List<ScanEvent>>(emptyList())
    val recentEvents: StateFlow<List<ScanEvent>> = _recentEvents.asStateFlow()

    /**
     * Record a scan if scanEnabled.
     * onResult(true)  => recorded
     * onResult(false) => ignored (dupe) OR blocked (not on scan tab)
     */
    fun record(codeRaw: String, onResult: (Boolean) -> Unit = {}) {
        val code = codeRaw.trim()
        if (code.isEmpty()) {
            onResult(false)
            return
        }

        // If user scans outside Scan tab, do not record
        if (!_scanEnabled.value) {
            onResult(false)
            return
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val countAfter = store.recordScan(code, now) // Int? (null = ignored)

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

    fun undo() {
        viewModelScope.launch {
            store.undoLast()
            // Optional: if you want the recent list to reflect undo, tell me your preferred behavior.
        }
    }
}
