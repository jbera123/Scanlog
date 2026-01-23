package com.example.scanlog.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import androidx.annotation.RawRes

class SimpleBeep(
    context: Context,
    @RawRes private val soundResId: Int
) {
    private val appContext = context.applicationContext

    private val soundPool: SoundPool
    private var soundId: Int = 0
    private var loaded: Boolean = false

    init {
        soundPool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
        } else {
            @Suppress("DEPRECATION")
            SoundPool(2, AudioManager.STREAM_ALARM, 0)
        }

        soundId = soundPool.load(appContext, soundResId, 1)
        soundPool.setOnLoadCompleteListener { _, id, status ->
            if (id == soundId && status == 0) loaded = true
        }
    }

    fun play(volume: Float = 1.0f, rate: Float = 1.0f) {
        if (!loaded || soundId == 0) return
        val v = volume.coerceIn(0f, 1f)
        val r = rate.coerceIn(0.5f, 2.0f)
        soundPool.play(soundId, v, v, 1, 0, r)
    }

    fun release() {
        runCatching { soundPool.release() }
    }
}
