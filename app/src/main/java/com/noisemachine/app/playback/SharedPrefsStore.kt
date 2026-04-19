package com.noisemachine.app.playback

import android.content.Context

/**
 * Production [PrefsStore] backed by [android.content.SharedPreferences].
 * Reads are synchronous (negligible for a few scalars); writes use `apply()`.
 */
class SharedPrefsStore(context: Context) : PrefsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var color: Float
        get() = prefs.getFloat(KEY_COLOR, DEFAULT_COLOR)
        set(value) { prefs.edit().putFloat(KEY_COLOR, value).apply() }

    override var timerDurationMs: Long
        get() = prefs.getLong(KEY_TIMER_DURATION, DEFAULT_TIMER_DURATION)
        set(value) { prefs.edit().putLong(KEY_TIMER_DURATION, value).apply() }

    override var texture: Float
        get() = prefs.getFloat(KEY_TEXTURE, DEFAULT_TEXTURE)
        set(value) { prefs.edit().putFloat(KEY_TEXTURE, value).apply() }

    override var stereoWidth: Float
        get() = prefs.getFloat(KEY_STEREO_WIDTH, DEFAULT_STEREO_WIDTH)
        set(value) { prefs.edit().putFloat(KEY_STEREO_WIDTH, value).apply() }

    override var microDriftDepth: Float
        get() = prefs.getFloat(KEY_MICRO_DRIFT_DEPTH, DEFAULT_MICRO_DRIFT_DEPTH)
        set(value) { prefs.edit().putFloat(KEY_MICRO_DRIFT_DEPTH, value).apply() }

    override var fadeInMs: Long
        get() = prefs.getLong(KEY_FADE_IN_MS, DEFAULT_FADE_IN_MS)
        set(value) { prefs.edit().putLong(KEY_FADE_IN_MS, value).apply() }

    override var fadeOutMs: Long
        get() = prefs.getLong(KEY_FADE_OUT_MS, DEFAULT_FADE_OUT_MS)
        set(value) { prefs.edit().putLong(KEY_FADE_OUT_MS, value).apply() }

    companion object {
        private const val PREFS_NAME = "noise_machine_prefs"
        private const val KEY_COLOR = "color"
        private const val KEY_TIMER_DURATION = "timer_duration_ms"
        private const val KEY_TEXTURE = "texture"
        private const val KEY_STEREO_WIDTH = "stereo_width"
        private const val KEY_MICRO_DRIFT_DEPTH = "micro_drift_depth"
        private const val KEY_FADE_IN_MS = "fade_in_ms"
        private const val KEY_FADE_OUT_MS = "fade_out_ms"
        private const val DEFAULT_COLOR = 0f
        private const val DEFAULT_TIMER_DURATION = 0L
        private const val DEFAULT_TEXTURE = 0f
        private const val DEFAULT_STEREO_WIDTH = 0f
        private const val DEFAULT_MICRO_DRIFT_DEPTH = 0f
        private const val DEFAULT_FADE_IN_MS = 2_000L
        private const val DEFAULT_FADE_OUT_MS = 5_000L
    }
}
