package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ScanEvent {
    data class Success(val code: String) : ScanEvent()
    data class NotLogged(val code: String) : ScanEvent()
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app)

    val todayCounts: StateFlow<Map<String, Int>> =
        store.todayCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Gate scanning so other tabs do not log scans.
    private var scanEnabled: Boolean = true

    private val _events = MutableSharedFlow<ScanEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    fun setScanEnabled(enabled: Boolean) {
        scanEnabled = enabled
    }

    fun onScannerInput(raw: String) {
        val code = raw.trim()
        if (code.isEmpty()) return

        if (!scanEnabled) {
            _events.tryEmit(ScanEvent.NotLogged(code))
            return
        }

        viewModelScope.launch {
            store.recordScan(code)
            _events.tryEmit(ScanEvent.Success(code))
        }
    }

    fun undo() {
        viewModelScope.launch { store.undoLast() }
    }
}
