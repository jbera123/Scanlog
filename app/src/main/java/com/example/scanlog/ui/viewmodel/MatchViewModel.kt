package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.scanlog.R
import com.example.scanlog.data.RfidBarcodeCatalog
import com.example.scanlog.util.SimpleBeep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class MatchState {
    object Idle : MatchState()
    data class RfidScanned(
        val tagId: String,
        val label: String,
        val expectedBarcode: String
    ) : MatchState()
    data class Result(
        val matched: Boolean,
        val rfidLabel: String,
        val scannedBarcode: String,
        val expectedBarcode: String
    ) : MatchState()
}

class MatchViewModel(app: Application) : AndroidViewModel(app) {

    private val catalog = RfidBarcodeCatalog(app.applicationContext)
    private val beep = SimpleBeep(app.applicationContext, R.raw.beep)

    private val _state = MutableStateFlow<MatchState>(MatchState.Idle)
    val state: StateFlow<MatchState> = _state.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun onRfidScanned(tagIdRaw: String) {
        val tagId = tagIdRaw.trim().uppercase()
        if (tagId.isEmpty()) return
        // Ignore RFID while showing result (auto-reset handles it)
        if (_state.value is MatchState.Result) return

        val entry = catalog.lookup(tagId)
        if (entry == null) {
            _snackbarMessage.value = "Unknown RFID tag: $tagId"
            return
        }
        _state.value = MatchState.RfidScanned(
            tagId = tagId,
            label = entry.label,
            expectedBarcode = entry.barcode
        )
    }

    fun onBarcodeScanned(codeRaw: String) {
        val current = _state.value as? MatchState.RfidScanned ?: return
        val code = codeRaw.trim().uppercase()
        if (code.isEmpty()) return

        _state.value = MatchState.Result(
            matched = code == current.expectedBarcode,
            rfidLabel = current.label,
            scannedBarcode = code,
            expectedBarcode = current.expectedBarcode
        )
    }

    fun playBeep(volume: Float = 1.0f, rate: Float = 1.15f) {
        beep.play(volume = volume, rate = rate)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun reset() {
        _state.value = MatchState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        beep.release()
    }
}
