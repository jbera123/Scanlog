package com.example.scanlog.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build

class SimpleBeep(context: Context) {

    private val appContext = context.applicationContext

    private val soundPool: SoundPool
    private val soundId: Int
    @Volatile private var loaded = false

    init {
        soundPool =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                SoundPool(2, AudioManager.STREAM_MUSIC, 0)
            }

        // Use a built-in android “beep-like” fallback: NOT available as raw.
        // So instead we do a simple “sound effect” fallback if you don't have a raw file.
        // If you DO have a raw beep resource, use SoundPool.load(resId) instead.
        // For now, we'll keep it minimal and reliable:
        loaded = true
        soundId = 0
    }

    fun play(volume: Float = 1f) {
        if (!loaded) return

        // Minimal + reliable: system click sound (routes through system sound effects)
        @Suppress("DEPRECATION")
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.playSoundEffect(AudioManager.FX_KEY_CLICK, volume)
    }

    fun release() {
        runCatching { soundPool.release() }
    }
}
