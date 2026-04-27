package com.movingfingerstudios.noisemachine.audio

/**
 * Stereo decorrelation stage using a first-order all-pass filter.
 *
 * Takes a mono `FloatArray` and produces an interleaved stereo `ShortArray`
 * (Int16 PCM) suitable for `AudioTrack.write`. Replaces the inline
 * `floatMonoToInt16Stereo` that previously lived in [AudioEngine].
 *
 * At width=0 both channels are identical (mono, D-5 default behavior).
 * At width>0 the R channel is a crossfade between the dry mono signal and
 * an all-pass-filtered version, creating subtle phase differences that
 * produce natural spatial width without changing spectral content.
 *
 * The all-pass filter is first-order:
 * ```
 * y[n] = coeff * x[n] + x[n-1] - coeff * y[n-1]
 * ```
 * where `coeff` controls the frequency at which 90° phase shift occurs.
 * A value around 0.5–0.7 puts the crossover in the mid-frequency range,
 * producing a broad decorrelation effect.
 *
 * Width range: 0.0 (mono) to 1.0 (maximum decorrelation). Values above
 * ~0.3 are not recommended for sleep use — the default should stay
 * restrained per D-5.
 *
 * Allocation-free. Not thread-safe. Use one instance per audio thread.
 */
class StereoStage {

    /** All-pass filter state: previous input sample. */
    private var apX1: Float = 0f

    /** All-pass filter state: previous output sample. */
    private var apY1: Float = 0f

    /**
     * Convert mono float samples to interleaved stereo Int16 with optional
     * decorrelation.
     *
     * L channel = mono signal (direct).
     * R channel = mono × (1 − width) + allpass(mono) × width.
     *
     * At width=0 this is equivalent to the old `floatMonoToInt16Stereo`:
     * both channels carry the identical sample.
     *
     * @param mono input mono samples in [-1.0, 1.0]
     * @param stereo output interleaved stereo Int16 buffer (size ≥ frames × 2)
     * @param frames number of mono frames to process
     * @param width decorrelation width in [0.0, 1.0]; 0 = mono, >0 = stereo spread
     */
    fun processToStereo(
        mono: FloatArray,
        stereo: ShortArray,
        frames: Int,
        width: Float,
    ) {
        val w = width.coerceIn(0f, 1f)

        if (w < WIDTH_EPSILON) {
            // Fast path: mono (identical channels), no all-pass needed.
            monoToStereo(mono, stereo, frames)
            return
        }

        val dry = 1f - w
        var x1 = apX1
        var y1 = apY1
        var i = 0
        var j = 0
        while (i < frames) {
            val x = mono[i]

            // First-order all-pass: y[n] = c*x[n] + x[n-1] - c*y[n-1]
            val apOut = ALLPASS_COEFF * x + x1 - ALLPASS_COEFF * y1
            x1 = x
            y1 = apOut

            // L = dry mono
            val l = toInt16(x)
            // R = crossfade between dry mono and all-pass output
            val r = toInt16(dry * x + w * apOut)

            stereo[j] = l
            stereo[j + 1] = r
            i++
            j += 2
        }
        apX1 = x1
        apY1 = y1
    }

    /**
     * Reset filter state. Call when starting a new playback session.
     */
    fun reset() {
        apX1 = 0f
        apY1 = 0f
    }

    /**
     * Fast-path mono duplication (width=0). Identical to the old
     * `floatMonoToInt16Stereo` in AudioEngine.
     */
    private fun monoToStereo(mono: FloatArray, stereo: ShortArray, frames: Int) {
        var i = 0
        var j = 0
        while (i < frames) {
            val s = toInt16(mono[i])
            stereo[j] = s
            stereo[j + 1] = s
            i++
            j += 2
        }
    }

    companion object {
        /**
         * All-pass coefficient. Controls the frequency at which 90° phase
         * shift occurs. 0.6 puts the crossover around 1–2 kHz at 44100 Hz,
         * producing broad decorrelation across the audible range.
         */
        private const val ALLPASS_COEFF = 0.6f

        /** Width values below this are treated as zero (mono fast path). */
        private const val WIDTH_EPSILON = 0.001f

        /** Convert a float sample in [-1.0, 1.0] to Int16, clip-safe. */
        private fun toInt16(v: Float): Short = when {
            v >= 1.0f -> Short.MAX_VALUE
            v <= -1.0f -> Short.MIN_VALUE
            else -> (v * 32767.0f).toInt().toShort()
        }
    }
}
