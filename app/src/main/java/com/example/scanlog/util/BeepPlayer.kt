package com.example.scanlog.util

import android.media.AudioManager
import android.media.ToneGenerator

class BeepPlayer {

    private val tone = ToneGenerator(
        AudioManager.STREAM_MUSIC, // loud, bypasses media volume
        100 // max volume (0–100)
    )

    fun playSuccess() {
        // Short, sharp beep (good for fast scanning ~1/sec)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun playError() {
        // Distinct error tone
        tone.startTone(ToneGenerator.TONE_PROP_NACK, 200)
    }

    fun release() {
        tone.release()
    }
}
