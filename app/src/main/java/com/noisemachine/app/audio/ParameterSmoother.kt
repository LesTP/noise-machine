package com.noisemachine.app.audio

/**
 * Lock-free, allocation-free exponential parameter ramp.
 *
 * Designed for the audio render thread: the UI thread sets a target value
 * via [setTarget] (`@Volatile` write), and the audio thread calls [next]
 * once per sample (or once per buffer at a reduced rate) to get the current
 * smoothed value.
 *
 * The ramp uses a one-pole exponential smoother:
 * ```
 * current += (target - current) * alpha
 * ```
 * where `alpha = 1 - exp(-1 / (sampleRate * timeSeconds))`.
 *
 * When `alpha` is 1.0 (instant mode), `next()` snaps to the target with
 * no ramping — used for initial value setup.
 *
 * Thread safety: [setTarget] is safe to call from any thread. [next] must
 * only be called from one thread (the audio render thread). No locks.
 *
 * @param initialValue starting value (returned immediately without ramping)
 * @param sampleRate samples per second (e.g. 44100)
 * @param timeSeconds ramp time constant in seconds; ~63% of the step is
 *   covered in one time constant, ~99% in five. 0.0 = instant (no smoothing).
 */
class ParameterSmoother(
    initialValue: Float,
    sampleRate: Int = 44_100,
    timeSeconds: Float = 0.05f,
) {
    @Volatile
    private var target: Float = initialValue

    private var current: Float = initialValue

    private val alpha: Float = if (timeSeconds <= 0f || sampleRate <= 0) {
        1.0f
    } else {
        val tau = sampleRate.toFloat() * timeSeconds
        1.0f - kotlin.math.exp(-1.0f / tau)
    }

    /**
     * Set the ramp target. Safe to call from any thread.
     */
    fun setTarget(value: Float) {
        target = value
    }

    /**
     * Advance the ramp by one sample and return the current smoothed value.
     * Must be called from the audio thread only (not thread-safe for reads).
     * Allocation-free.
     */
    fun next(): Float {
        val t = target // single volatile read
        current += (t - current) * alpha
        return current
    }
}
