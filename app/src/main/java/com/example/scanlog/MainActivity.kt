package com.example.scanlog

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.scanlog.rfid.RfidController
import com.example.scanlog.ui.theme.ScanlogTheme
import com.example.scanlog.util.BldScanner

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScanlogTheme {
                AppNav()
            }
        }
    }

    /**
     * Device sleep can drop the UHF serial/power rail and close the laser while
     * leaving our in-app state stale, so the trigger does nothing on wake. Re-verify
     * the reader and re-wake the laser on every resume so scanning works immediately.
     */
    override fun onResume() {
        super.onResume()
        RfidController.ensureConnected()
        BldScanner.init(applicationContext)
    }

    /**
     * Intercept the hardware trigger button. PDAs dispatch different keycodes
     * depending on vendor — the demo used 619, but other common codes are 280,
     * 285, 293, 523, 700+, 1011. We accept the whole set and rely on
     * RfidController.setGate(...) (driven by AppNav) to gate whether a press
     * actually starts inventory.
     *
     * Any time the app receives an unknown keycode we also log it so we can
     * narrow down the real trigger code on your specific PDA.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Only hijack the trigger when RFID is active. In barcode-only mode (or any
        // non-RFID screen) the gate is closed, so we must let the key fall through
        // to the system barcode scanner service — otherwise the laser never fires.
        if (code in TRIGGER_KEYCODES && RfidController.isGateOpen()) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Fire on every DOWN, including auto-repeats while the trigger
                    // is held — RfidController uses them as the hold keep-alive.
                    if (event.repeatCount == 0) Log.d(TAG, "trigger DOWN keyCode=$code")
                    RfidController.triggerPress()
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    Log.d(TAG, "trigger UP keyCode=$code")
                    RfidController.triggerRelease()
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            code !in SYSTEM_KEYCODES
        ) {
            // Helps identify the real trigger keycode on unknown hardware.
            Log.d(TAG, "unhandled keyCode=$code (add to TRIGGER_KEYCODES if this is the trigger)")
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val TAG = "MainActivity"

        // Broad set of keycodes known to be used by various PDA scan triggers.
        // If your PDA uses a different code, check logcat for the "unhandled
        // keyCode=…" line above and add it here.
        private val TRIGGER_KEYCODES = setOf(
            139,                  // KEYCODE_MENU on some PDAs / side button
            280, 281, 282, 283,   // Xcheng / Chainway side + trigger
            285, 286, 287, 288,
            289, 290, 291, 292, 293,
            523, 524,             // rear trigger on some units
            619,                  // demo's trigger (RF-M6001)
            700, 701, 702, 703, 704, 705, 706, 707,
            710, 711,
            1011, 1012
        )

        // Don't spam logs for these — they're normal UI / system keys.
        private val SYSTEM_KEYCODES = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB
        )
    }
}
