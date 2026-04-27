package com.movingfingerstudios.noisemachine.audio

import java.util.SplittableRandom

/**
 * Allocation-free white-noise source.
 *
 * Generates uniformly distributed `Float` samples in `[-1.0f, 1.0f)` on demand.
 * Designed for the audio render thread: [fill] performs no allocations and is
 * safe to call repeatedly from a single thread.
 *
 * Not thread-safe. Use one instance per audio thread.
 */
class NoiseSource(seed: Long = System.nanoTime()) {

    private val rng = SplittableRandom(seed)

    /**
     * Fills [buffer] from index 0 up to (exclusive) [length] with uniform
     * white-noise samples in `[-1.0f, 1.0f)`.
     *
     * @param length number of samples to write; must be in `0..buffer.size`.
     */
    fun fill(buffer: FloatArray, length: Int = buffer.size) {
        require(length in 0..buffer.size) {
            "length=$length out of buffer.size=${buffer.size}"
        }
        var i = 0
        while (i < length) {
            // SplittableRandom.nextDouble() returns [0.0, 1.0). Map to [-1.0, 1.0).
            buffer[i] = (rng.nextDouble() * 2.0 - 1.0).toFloat()
            i++
        }
    }
}
