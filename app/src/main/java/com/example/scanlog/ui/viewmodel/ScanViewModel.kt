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
