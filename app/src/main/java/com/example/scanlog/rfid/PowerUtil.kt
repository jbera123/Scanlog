package com.example.scanlog.rfid

import android.util.Log
import java.io.FileWriter

/**
 * Toggles the UHF module's power rail via the PDA firmware's GPIO sysfs node.
 * Mirrors the demo's utils/PowerUtil.java — write "1" to power on, "0" to power off.
 */
object PowerUtil {
    private const val PATH = "/proc/gpiocontrol/set_uhf"

    fun power(state: String) {
        runCatching {
            FileWriter(PATH).use { it.write(state) }
            Thread.sleep(300)
            Log.i("RfidPower", "power=$state path=$PATH")
        }.onFailure { Log.w("RfidPower", "power($state) failed: ${it.message}") }
    }
}
