package com.movingfingerstudios.noisemachine.audio

import kotlin.math.PI

/**
 * Output safety stage: DC blocker + gain compensation + hard clip.
 *
 * Sits after [TextureShaper] in the render pipeline and ensures the
 * output is clean and bounded regardless of Color value.
 *
 * Three stages applied in order:
 * 1. **DC blocker** (D-17) — first-order high-pass at ~[DC_BLOCKER_FREQ] Hz.
 *    Removes any DC offset introduced by the shelving filters at the
 *    dark end of the Color range. Uses a leaky integrator subtraction.
 * 2. **Gain compensation** (D-19) — static Color-indexed gain factor that
 *    keeps perceived loudness approximately constant as spectral tilt
 *    changes. At Color=0 (flat white) the gain is slightly reduced
 *    because white noise has more high-frequency energy that sounds
 *    louder perceptually; at Color=1 (brown-ish) the low-shelf boost
 *    adds energy so gain is reduced to compensate.
 * 3. **Hard clip** — clamps every sample to [-1.0, 1.0] as a final
 *    safety net before conversion to Int16.
 *
 * All processing is allocation-free and in-place on the mono buffer.
 *
 * Not thread-safe. Use one instance per audio thread.
 *
 * @param sampleRate Hz (e.g. 44100)
 */
class GainSafety(sampleRate: Int = 44_100) {

    // DC blocker state (first-order high-pass via leaky integrator subtraction).
    // y[n] = x[n] - x_avg[n], where x_avg tracks a very slow moving average.
    // alpha close to 1.0 means very slow tracking = very low cutoff frequency.
    private val dcAlpha: Float = run {
        val w0 = 2.0 * PI * DC_BLOCKER_FREQ / sampleRate
        (1.0 - w0 / (1.0 + w0)).toFloat() // bilinear approximation
    }
    private var dcState: Float = 0f

    /**
     * Process [length] samples of [buffer] in-place.
     *
     * @param buffer mono sample buffer (modified in-place)
     * @param length number of samples to process
     * @param color current Color value in [0.0, 1.0], used for gain compensation
     */
    fun process(buffer: FloatArray, length: Int = buffer.size, color: Float) {
        val gain = compensationGain(color)
        var dc = dcState
        val a = dcAlpha
        var i = 0
        while (i < length) {
            // DC blocker: subtract slowly-tracked DC estimate.
            val x = buffer[i]
            dc = a * dc + (1f - a) * x
            var y = x - dc

            // Gain compensation.
            y *= gain

            // Hard clip.
            if (y > 1f) y = 1f
            else if (y < -1f) y = -1f

            buffer[i] = y
            i++
        }
        dcState = dc
    }

    /**
     * Reset DC blocker state. Call when starting a new playback session.
     */
    fun reset() {
        dcState = 0f
    }

    companion object {
        // DC blocker cutoff frequency in Hz (D-17).
        private const val DC_BLOCKER_FREQ = 20.0

        // Gain compensation curve (D-19).
        // At Color=0 (white): slight reduction because broadband noise
        //   with full HF content sounds louder perceptually.
        // At Color=0.5 (pink-ish): near unity — the perceptual midpoint.
        // At Color=1 (brown-ish): reduction to compensate for low-shelf
        //   energy boost from SpectralShaper.
        // Linear interpolation between these anchors.
        private const val GAIN_AT_WHITE = 0.85f  // Color=0
        private const val GAIN_AT_PINK = 0.95f   // Color=0.5
        private const val GAIN_AT_BROWN = 0.75f  // Color=1.0

        /**
         * Static Color-indexed compensation gain. Piecewise linear
         * between three anchor points.
         */
        internal fun compensationGain(color: Float): Float {
            val c = color.coerceIn(0f, 1f)
            return if (c <= 0.5f) {
                // Lerp white → pink over [0, 0.5]
                val t = c / 0.5f
                GAIN_AT_WHITE + (GAIN_AT_PINK - GAIN_AT_WHITE) * t
            } else {
                // Lerp pink → brown over [0.5, 1.0]
                val t = (c - 0.5f) / 0.5f
                GAIN_AT_PINK + (GAIN_AT_BROWN - GAIN_AT_PINK) * t
            }
        }
    }
}
