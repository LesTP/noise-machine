package com.noisemachine.app.audio

/**
 * Slow LFO producing a small Color offset for subtle tonal wandering.
 *
 * Generates a triangle-wave oscillation at [BASE_FREQ_HZ] (≈0.05 Hz, ~20 s
 * period). The output is a signed offset in `[-MAX_OFFSET, +MAX_OFFSET]`
 * scaled by the current depth parameter. At depth=0 the offset is always 0;
 * at depth=1 the offset swings ±[MAX_OFFSET].
 *
 * The caller (AudioEngine render loop) calls [nextBlock] once per buffer to
 * get the current offset, then adds it to the smoothed Color value before
 * passing to SpectralShaper.
 *
 * Depth changes are smoothed via a one-pole filter to avoid abrupt jumps
 * when the user adjusts the Settings slider.
 *
 * Allocation-free. Not thread-safe — use one instance per audio thread.
 * Depth target may be set from any thread (`@Volatile`).
 *
 * @param sampleRate samples per second (e.g. 44100)
 */
class MicroDrift(private val sampleRate: Int = 44_100) {

    /**
     * Triangle-wave phase in [0.0, 1.0). One full cycle = one period.
     * Advances by `BASE_FREQ_HZ / sampleRate` per sample.
     */
    private var phase: Double = 0.0

    /** Per-sample phase increment. */
    private val phaseInc: Double = BASE_FREQ_HZ / sampleRate.toDouble()

    /** Depth target — set from any thread. */
    @Volatile
    private var depthTarget: Float = 0f

    /** Smoothed depth on the render thread. */
    private var depthCurrent: Float = 0f

    /** One-pole alpha for depth smoothing (~200 ms time constant). */
    private val depthAlpha: Float = run {
        val tau = sampleRate.toFloat() * DEPTH_SMOOTH_SECONDS
        1.0f - kotlin.math.exp(-1.0f / tau)
    }

    /**
     * Set the drift depth. Safe to call from any thread.
     *
     * @param depth in [0.0, 1.0]; 0 = no drift, 1 = maximum drift (±[MAX_OFFSET])
     */
    fun setDepth(depth: Float) {
        depthTarget = depth.coerceIn(0f, 1f)
    }

    /**
     * Advance the LFO by [frames] samples and return the current Color offset.
     *
     * The offset is computed once per block (not per sample) since the LFO
     * frequency is so low (~0.05 Hz) that per-sample resolution is unnecessary.
     *
     * @return Color offset to add to the base Color value
     */
    fun nextBlock(frames: Int): Float {
        // Smooth depth (per-block, closed-form step like ParameterSmoother.nextBlock)
        val dt = depthTarget
        if (depthAlpha >= 1f) {
            depthCurrent = dt
        } else {
            val retain = kotlin.math.exp(frames.toFloat() * kotlin.math.ln(1f - depthAlpha))
            depthCurrent += (dt - depthCurrent) * (1f - retain)
        }

        // Fast path: no drift
        if (depthCurrent < DEPTH_EPSILON) {
            // Still advance phase to avoid a jump when depth is re-enabled
            phase += phaseInc * frames
            phase -= kotlin.math.floor(phase) // wrap to [0, 1)
            return 0f
        }

        // Advance triangle-wave phase
        phase += phaseInc * frames
        phase -= kotlin.math.floor(phase) // wrap to [0, 1)

        // Triangle wave: maps phase [0,1) → output [-1, +1]
        // 0.0→0.25: rises  0→+1
        // 0.25→0.75: falls +1→-1
        // 0.75→1.0: rises -1→0
        val tri = when {
            phase < 0.25 -> (phase * 4.0).toFloat()
            phase < 0.75 -> (2.0 - phase * 4.0).toFloat()
            else -> (phase * 4.0 - 4.0).toFloat()
        }

        return tri * depthCurrent * MAX_OFFSET
    }

    /**
     * Reset LFO state. Call when starting a new playback session.
     */
    fun reset() {
        phase = 0.0
        depthTarget = 0f
        depthCurrent = 0f
    }

    companion object {
        /** LFO frequency in Hz. ~0.05 Hz → ~20 s period. */
        const val BASE_FREQ_HZ = 0.05

        /** Maximum Color offset at depth=1. */
        const val MAX_OFFSET = 0.05f

        /** Depth values below this are treated as zero. */
        private const val DEPTH_EPSILON = 0.0001f

        /** Depth smoother time constant in seconds. */
        private const val DEPTH_SMOOTH_SECONDS = 0.2f
    }
}
