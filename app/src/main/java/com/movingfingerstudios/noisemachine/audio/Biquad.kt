package com.movingfingerstudios.noisemachine.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generic second-order IIR filter (biquad) using Direct Form II Transposed.
 *
 * Processes mono `FloatArray` buffers in-place with zero allocations in the
 * hot path. State variables (`z1`, `z2`) are maintained between calls so the
 * filter is continuous across buffer boundaries.
 *
 * Coefficients are stored normalized (a0 = 1): `b0, b1, b2, a1, a2`.
 * Use the companion factory methods ([lowShelf], [highShelf]) to create
 * pre-configured instances, or call the instance [configureLowShelf] /
 * [configureHighShelf] methods to update coefficients in-place without
 * allocation (used by [SpectralShaper] during playback).
 *
 * Not thread-safe. Use one instance per audio thread.
 *
 * Reference: Audio EQ Cookbook by Robert Bristow-Johnson.
 */
class Biquad private constructor(
    private var b0: Float,
    private var b1: Float,
    private var b2: Float,
    private var a1: Float,
    private var a2: Float,
) {
    private var z1: Float = 0f
    private var z2: Float = 0f

    /**
     * Process [length] samples of [buffer] in-place. Allocation-free.
     */
    fun process(buffer: FloatArray, length: Int = buffer.size) {
        var s1 = z1
        var s2 = z2
        val cb0 = b0; val cb1 = b1; val cb2 = b2
        val ca1 = a1; val ca2 = a2
        var i = 0
        while (i < length) {
            val x = buffer[i]
            val y = cb0 * x + s1
            s1 = cb1 * x - ca1 * y + s2
            s2 = cb2 * x - ca2 * y
            buffer[i] = y
            i++
        }
        z1 = s1
        z2 = s2
    }

    /**
     * Update coefficients in-place without allocating a new Biquad.
     * Call between buffers (not mid-buffer) to avoid discontinuities;
     * SpectralShaper coordinates this via ParameterSmoother.
     */
    fun setCoefficients(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
        this.b0 = b0; this.b1 = b1; this.b2 = b2
        this.a1 = a1; this.a2 = a2
    }

    /**
     * Reconfigure as a low-shelf filter in-place. Allocation-free.
     *
     * @param sampleRate Hz (e.g. 44100)
     * @param freqHz shelf transition frequency
     * @param gainDb boost (positive) or cut (negative) in dB below [freqHz]
     * @param q shelf slope quality factor (0.707 = Butterworth)
     */
    fun configureLowShelf(
        sampleRate: Int,
        freqHz: Float,
        gainDb: Float,
        q: Float = 0.707f,
    ) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freqHz / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2f * q)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val a0 = (a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha
        val invA0 = 1f / a0

        setCoefficients(
            b0 = (a * ((a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha)) * invA0,
            b1 = (2f * a * ((a - 1f) - (a + 1f) * cosW)) * invA0,
            b2 = (a * ((a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha)) * invA0,
            a1 = (-2f * ((a - 1f) + (a + 1f) * cosW)) * invA0,
            a2 = ((a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha) * invA0,
        )
    }

    /**
     * Reconfigure as a high-shelf filter in-place. Allocation-free.
     *
     * @param sampleRate Hz (e.g. 44100)
     * @param freqHz shelf transition frequency
     * @param gainDb boost (positive) or cut (negative) in dB above [freqHz]
     * @param q shelf slope quality factor (0.707 = Butterworth)
     */
    fun configureHighShelf(
        sampleRate: Int,
        freqHz: Float,
        gainDb: Float,
        q: Float = 0.707f,
    ) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freqHz / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2f * q)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val a0 = (a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha
        val invA0 = 1f / a0

        setCoefficients(
            b0 = (a * ((a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha)) * invA0,
            b1 = (-2f * a * ((a - 1f) + (a + 1f) * cosW)) * invA0,
            b2 = (a * ((a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha)) * invA0,
            a1 = (2f * ((a - 1f) - (a + 1f) * cosW)) * invA0,
            a2 = ((a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha) * invA0,
        )
    }

    /**
     * Reset filter state to zero (silence). Use when starting a new
     * playback session to avoid transient pops from stale state.
     */
    fun reset() {
        z1 = 0f; z2 = 0f
    }

    companion object {
        /**
         * Create a low-shelf biquad.
         */
        fun lowShelf(
            sampleRate: Int,
            freqHz: Float,
            gainDb: Float,
            q: Float = 0.707f,
        ): Biquad = passthrough().apply { configureLowShelf(sampleRate, freqHz, gainDb, q) }

        /**
         * Create a high-shelf biquad.
         */
        fun highShelf(
            sampleRate: Int,
            freqHz: Float,
            gainDb: Float,
            q: Float = 0.707f,
        ): Biquad = passthrough().apply { configureHighShelf(sampleRate, freqHz, gainDb, q) }

        /**
         * Create a pass-through (unity) biquad. Useful as a default or
         * placeholder in a filter chain.
         */
        fun passthrough(): Biquad = Biquad(1f, 0f, 0f, 0f, 0f)
    }
}
