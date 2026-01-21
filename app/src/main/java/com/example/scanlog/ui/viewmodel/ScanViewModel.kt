package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)

    val todayCounts: StateFlow<Map<String, Int>> =
        store.todayCounts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    fun record(raw: String) {
        viewModelScope.launch { store.recordScan(raw) }
    }

    fun undo() {
        viewModelScope.launch { store.undoLast() }
    }
}
