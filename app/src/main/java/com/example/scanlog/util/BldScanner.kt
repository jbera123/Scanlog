package com.example.scanlog.util

import android.content.Context
import android.util.Log

/**
 * Reflection wrapper around `android.bld.ScanManager` used by the 条捷印 T02 PDA.
 *
 * That class only exists in the T02's system image; on Xcheng (or any other PDA)
 * the lookup fails and every call here is a silent no-op. So it's safe to call
 * `init()` once from the Application without polluting the build with
 * vendor-specific jars.
 *
 * What this fixes: the T02's scanner service starts in "input" mode by default,
 * meaning the trigger types characters into the focused EditText instead of
 * firing a broadcast. We force it into broadcast mode (OPMode = 0) and call
 * openScanner() so the trigger emits `scan.rcv.message` with our payload.
 */
object BldScanner {

    private const val TAG = "BldScanner"
    private const val CLASS_NAME = "android.bld.ScanManager"
    private const val OP_MODE_BROADCAST = 0

    @Volatile private var instance: Any? = null

    /** True if the T02 ScanManager class is present at runtime. */
    fun isAvailable(): Boolean = try {
        Class.forName(CLASS_NAME); true
    } catch (_: Throwable) { false }

    /**
     * Toggle continuous scan mode on the T02 laser. No-op on devices without
     * the BLD ScanManager.
     *
     * - true: laser keeps decoding while the trigger is held. Right for the
     *   RFID+Barcode workflow where one trigger-hold = a sweep.
     * - false: one decode per trigger press. Right for barcode-only counting.
     */
    fun setContinuous(continuous: Boolean) {
        val sm = instance ?: return
        runCatching {
            sm.javaClass.getMethod("setContinueScan", Boolean::class.javaPrimitiveType)
                .invoke(sm, continuous)
            Log.i(TAG, "setContinueScan($continuous)")
        }.onFailure { Log.w(TAG, "setContinueScan($continuous) failed: ${it.message}") }
    }

    /**
     * Best-effort init: get the singleton, force broadcast mode, open the scanner.
     * Safe to call repeatedly. Silently no-ops on devices without the class.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        try {
            val cls = Class.forName(CLASS_NAME)
            val getDefault = cls.getMethod("getDefaultInstance", Context::class.java)
            val sm = getDefault.invoke(null, ctx) ?: return
            instance = sm

            runCatching {
                cls.getMethod("setOPMode", Int::class.javaPrimitiveType)
                    .invoke(sm, OP_MODE_BROADCAST)
            }.onFailure { Log.w(TAG, "setOPMode failed: ${it.message}") }

            runCatching {
                cls.getMethod("openScanner").invoke(sm)
            }.onFailure { Log.w(TAG, "openScanner failed: ${it.message}") }

            Log.i(TAG, "BLD ScanManager initialized (broadcast mode)")
        } catch (_: ClassNotFoundException) {
            // Not a T02 — fine, host PDA has its own broadcast pipeline.
        } catch (t: Throwable) {
            Log.w(TAG, "BLD init unexpected failure: ${t.message}")
        }
    }
}
