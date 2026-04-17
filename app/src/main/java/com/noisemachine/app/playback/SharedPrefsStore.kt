package com.noisemachine.app.playback

import android.content.Context

/**
 * Production [PrefsStore] backed by [android.content.SharedPreferences].
 * Reads are synchronous (negligible for 2 scalars); writes use `apply()`.
 */
class SharedPrefsStore(context: Context) : PrefsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var color: Float
        get() = prefs.getFloat(KEY_COLOR, DEFAULT_COLOR)
        set(value) { prefs.edit().putFloat(KEY_COLOR, value).apply() }

    override var timerDurationMs: Long
        get() = prefs.getLong(KEY_TIMER_DURATION, DEFAULT_TIMER_DURATION)
        set(value) { prefs.edit().putLong(KEY_TIMER_DURATION, value).apply() }

    companion object {
        private const val PREFS_NAME = "noise_machine_prefs"
        private const val KEY_COLOR = "color"
        private const val KEY_TIMER_DURATION = "timer_duration_ms"
        private const val DEFAULT_COLOR = 0f
        private const val DEFAULT_TIMER_DURATION = 0L
    }
}
