package com.movingfingerstudios.noisemachine.audio

/**
 * Color-driven spectral shaper using cascaded biquad shelving filters.
 *
 * Maps a Color value in [0.0, 1.0] to coordinated low-shelf and high-shelf
 * parameters that tilt the noise spectrum from flat/white (Color 0) through
 * pink-like (Color ~0.5) to brown-like (Color 1.0). This is the core DSP
 * stage that gives the app its continuous spectral-character slider.
 *
 * Topology (D-16): two biquad sections — low-shelf boost + high-shelf cut.
 * The coordinated gains produce a natural spectral tilt rather than a simple
 * low-pass sweep. Exact gain curves are initial defaults; perceptual tuning
 * happens in Phase 2 Step 7 (Refine).
 *
 * Color → coefficient mapping (D-20):
 * - Low shelf at [LOW_SHELF_FREQ] Hz: 0 dB at Color=0, +[LOW_SHELF_MAX_DB] dB at Color=1
 * - High shelf at [HIGH_SHELF_FREQ] Hz: 0 dB at Color=0, +[HIGH_SHELF_MIN_DB] dB at Color=1
 *
 * Coefficients are recalculated only when Color changes (once per buffer,
 * not per sample). The per-sample inner loop is pure biquad processing —
 * allocation-free and branch-free.
 *
 * Not thread-safe. Use one instance per audio thread.
 *
 * @param sampleRate Hz (e.g. 44100)
 */
class SpectralShaper(private val sampleRate: Int = 44_100) {

    private val lowShelf = Biquad.passthrough()
    private val highShelf = Biquad.passthrough()

    /** Last Color value used for coefficient calculation. NaN forces first update. */
    private var lastColor: Float = Float.NaN

    /**
     * Process [length] samples of [buffer] in-place using the current Color.
     *
     * If [color] differs from the last call, coefficients are recalculated
     * before processing. This is expected to be called once per render buffer
     * with a smoothed Color value from [ParameterSmoother].
     *
     * @param buffer mono sample buffer (modified in-place)
     * @param length number of samples to process
     * @param color spectral tilt value in [0.0, 1.0]; 0 = white, 1 = brown-like
     */
    fun process(buffer: FloatArray, length: Int = buffer.size, color: Float) {
        if (color != lastColor) {
            updateCoefficients(color)
            lastColor = color
        }
        lowShelf.process(buffer, length)
        highShelf.process(buffer, length)
    }

    /**
     * Reset filter state and force coefficient recalculation on next [process].
     * Call when starting a new playback session.
     */
    fun reset() {
        lowShelf.reset()
        highShelf.reset()
        lastColor = Float.NaN
    }

    private fun updateCoefficients(color: Float) {
        val c = color.coerceIn(0f, 1f)

        if (c < COLOR_EPSILON) {
            // Near-white: bypass to passthrough (avoids trig for no audible effect).
            lowShelf.setCoefficients(1f, 0f, 0f, 0f, 0f)
            highShelf.setCoefficients(1f, 0f, 0f, 0f, 0f)
            return
        }

        lowShelf.configureLowShelf(sampleRate, LOW_SHELF_FREQ, c * LOW_SHELF_MAX_DB)
        highShelf.configureHighShelf(sampleRate, HIGH_SHELF_FREQ, c * HIGH_SHELF_MIN_DB)
    }

    companion object {
        // Initial curve — tuned in Step 7 (Refine).
        // Low shelf: boost low end as Color increases.
        private const val LOW_SHELF_FREQ = 250f
        private const val LOW_SHELF_MAX_DB = 10f // +10 dB at Color=1.0

        // High shelf: cut high end as Color increases.
        private const val HIGH_SHELF_FREQ = 2500f
        private const val HIGH_SHELF_MIN_DB = -14f // -14 dB at Color=1.0

        // Color values below this are treated as zero (passthrough).
        private const val COLOR_EPSILON = 0.001f
    }
}
