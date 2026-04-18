package com.noisemachine.app.audio

/**
 * Variable-rate zero-order hold (sample decimation) for smooth ↔ grainy texture.
 *
 * At texture=0, every sample passes through unchanged (silky smooth).
 * At texture=1, each sample is held/repeated for [MAX_HOLD] frames before
 * the next input sample is used, creating audible graininess without
 * changing the spectral tilt (that's Color's job via [SpectralShaper]).
 *
 * The hold length is `1 + floor(texture * (MAX_HOLD - 1))`:
 * - texture=0.0 → holdLength=1 (passthrough)
 * - texture=0.5 → holdLength≈3–4
 * - texture=1.0 → holdLength=[MAX_HOLD]
 *
 * Sits between [SpectralShaper] and [GainSafety] in the render pipeline
 * (see ARCHITECTURE.md).
 *
 * Allocation-free. Not thread-safe. Use one instance per audio thread.
 */
class TextureShaper {

    /** The sample currently being held/repeated. */
    private var heldSample: Float = 0f

    /** How many more times to repeat [heldSample] before grabbing the next input. */
    private var holdCounter: Int = 0

    /**
     * Process [length] samples of [buffer] in-place.
     *
     * @param buffer mono sample buffer (modified in-place)
     * @param length number of samples to process
     * @param texture grain amount in [0.0, 1.0]; 0 = smooth (passthrough), 1 = maximum grain
     */
    fun process(buffer: FloatArray, length: Int = buffer.size, texture: Float) {
        val t = texture.coerceIn(0f, 1f)

        // Fast path: texture=0 means holdLength=1, which is passthrough.
        if (t < TEXTURE_EPSILON) {
            // Reset hold state so switching from textured→smooth is clean.
            holdCounter = 0
            return
        }

        val holdLength = 1 + (t * (MAX_HOLD - 1)).toInt()
        var i = 0
        while (i < length) {
            if (holdCounter <= 0) {
                // Grab a new sample and start holding it.
                heldSample = buffer[i]
                holdCounter = holdLength
            }
            buffer[i] = heldSample
            holdCounter--
            i++
        }
    }

    /**
     * Reset hold state. Call when starting a new playback session.
     */
    fun reset() {
        heldSample = 0f
        holdCounter = 0
    }

    companion object {
        /**
         * Maximum hold length in samples at texture=1.0.
         * At 44100 Hz, hold=6 gives an effective "resolution" of ~7350 Hz,
         * which sounds distinctly grainy without being harsh.
         */
        const val MAX_HOLD = 6

        /** Texture values below this are treated as zero (passthrough). */
        private const val TEXTURE_EPSILON = 0.001f
    }
}
