package com.example.scanlog.util

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {

    // Small delay makes it feel "one event" with many sound engines.
    private const val VIBE_DELAY_MS = 10L
    private const val VIBE_DURATION_MS = 35L

    fun beepThenVibrate(
        context: Context,
        playBeep: () -> Unit
    ) {
        // Beep now
        playBeep()

        // Vibrate shortly after (main thread)
        Handler(Looper.getMainLooper()).postDelayed({
            strongShortVibration(context)
        }, VIBE_DELAY_MS)
    }

    fun strongShortVibration(context: Context) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    VIBE_DURATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBE_DURATION_MS)
        }
    }
}
