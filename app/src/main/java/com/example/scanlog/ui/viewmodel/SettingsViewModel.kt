package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.RfidRange
import com.example.scanlog.data.ScanMode
import com.example.scanlog.data.ScanStore
import com.example.scanlog.rfid.RfidController
import com.example.scanlog.util.BldScanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    // IMPORTANT: use ScanStore so settings and scan logic read/write the same place
    private val store = ScanStore(app.applicationContext)

    val dupEnabled: StateFlow<Boolean> =
        store.duplicateGuardEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    val dupWindowMs: StateFlow<Long> =
        store.duplicateWindowMs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 2000L
        )

    fun setDupEnabled(v: Boolean) {
        viewModelScope.launch {
            store.setDuplicateGuardEnabled(v)
        }
    }

    fun setDupWindowSeconds(seconds: Long) {
        viewModelScope.launch {
            store.setDuplicateWindowMs(seconds * 1000L)
        }
    }

    val rfidRange: StateFlow<RfidRange> =
        store.rfidRange.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RfidRange.STRONG
        )

    fun setRfidRange(range: RfidRange) {
        viewModelScope.launch {
            store.setRfidRange(range)
            RfidController.setRange(range)
        }
    }

    val rssiFloor: StateFlow<Int> =
        store.rssiFloor.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0
        )

    fun setRssiFloor(dbm: Int) {
        viewModelScope.launch {
            store.setRssiFloor(dbm)
            RfidController.setRssiFloor(dbm)
        }
    }

    val scanMode: StateFlow<ScanMode> =
        store.scanMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ScanMode.BARCODE_ONLY
        )

    fun setScanMode(mode: ScanMode) {
        viewModelScope.launch {
            store.setScanMode(mode)
            // If switching out of RFID mode, make sure the reader isn't running.
            if (mode == ScanMode.BARCODE_ONLY) {
                RfidController.setGate(false)
            }
            // Tie the T02 laser's continuous flag to the workflow:
            //   RFID+Barcode → continuous (sweep with held trigger)
            //   Barcode-only → one shot per press (clean counting)
            // No-op on PDAs without the BLD ScanManager.
            BldScanner.setContinuous(mode == ScanMode.RFID_AND_BARCODE)
        }
    }

    val allowRepeatedScans: StateFlow<Boolean> =
        store.allowRepeatedScans.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun setAllowRepeatedScans(enabled: Boolean) {
        viewModelScope.launch {
            store.setAllowRepeatedScans(enabled)
        }
    }
}
