package com.noisemachine.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Biquad] covering Phase-2 test spec T11, T12, T13
 * (see DEVPLAN.md, Phase 2 Test Spec).
 *
 * Frequency-response tests use a band-energy comparison approach:
 * feed seeded white noise through the filter, then measure RMS energy
 * in low vs. high frequency bands by applying simple moving-average
 * smoothing (low-band proxy) and subtracting (high-band proxy).
 *
 * This avoids an FFT dependency while still verifying that the filter
 * shifts energy between bands as expected.
 */
class BiquadTest {

    /**
     * Generate a white-noise buffer with known seed for reproducible tests.
     */
    private fun whiteNoise(size: Int, seed: Long = 0xBEEFL): FloatArray {
        val src = NoiseSource(seed)
        val buf = FloatArray(size)
        src.fill(buf)
        return buf
    }

    /**
     * Compute RMS of a buffer.
     */
    private fun rms(buf: FloatArray): Double {
        var sum = 0.0
        for (v in buf) sum += v.toDouble() * v.toDouble()
        return kotlin.math.sqrt(sum / buf.size)
    }

    /**
     * Extract low-frequency content via a simple moving-average filter
     * (acts as a crude low-pass). Window size controls the cutoff —
     * larger window = lower cutoff.
     */
    private fun lowBandEnergy(buf: FloatArray, windowSize: Int = 64): Double {
        val smoothed = FloatArray(buf.size)
        var sum = 0.0
        for (i in buf.indices) {
            sum += buf[i]
            if (i >= windowSize) sum -= buf[i - windowSize]
            val count = minOf(i + 1, windowSize)
            smoothed[i] = (sum / count).toFloat()
        }
        return rms(smoothed)
    }

    /**
     * Extract high-frequency content by subtracting the low-band
     * smoothed signal from the original.
     */
    private fun highBandEnergy(buf: FloatArray, windowSize: Int = 64): Double {
        val smoothed = FloatArray(buf.size)
        var sum = 0.0
        for (i in buf.indices) {
            sum += buf[i]
            if (i >= windowSize) sum -= buf[i - windowSize]
            val count = minOf(i + 1, windowSize)
            smoothed[i] = (sum / count).toFloat()
        }
        // High = original - smoothed
        val high = FloatArray(buf.size)
        for (i in buf.indices) high[i] = buf[i] - smoothed[i]
        return rms(high)
    }

    /**
     * T11 — low-shelf with positive gain boosts low frequencies.
     *
     * Compare low-band and high-band energy ratios between filtered and
     * unfiltered white noise. After a low-shelf boost, the ratio of
     * low-to-high energy should increase.
     */
    @Test
    fun low_shelf_boosts_low_frequencies() {
        val buf = whiteNoise(44_100) // 1 second at 44.1 kHz
        val reference = buf.copyOf()

        val filter = Biquad.lowShelf(
            sampleRate = 44_100,
            freqHz = 500f,
            gainDb = 12f,
        )
        filter.process(buf)

        val refLowHigh = lowBandEnergy(reference) / highBandEnergy(reference)
        val filtLowHigh = lowBandEnergy(buf) / highBandEnergy(buf)

        assertTrue(
            "Low-shelf boost should increase low/high ratio: " +
                "ref=$refLowHigh, filtered=$filtLowHigh",
            filtLowHigh > refLowHigh * 1.5, // at least 50% increase in ratio
        )
    }

    /**
     * T12 — high-shelf with negative gain cuts high frequencies.
     *
     * After a high-shelf cut, the ratio of low-to-high energy should increase
     * (because highs are attenuated).
     */
    @Test
    fun high_shelf_cuts_high_frequencies() {
        val buf = whiteNoise(44_100)
        val reference = buf.copyOf()

        val filter = Biquad.highShelf(
            sampleRate = 44_100,
            freqHz = 2000f,
            gainDb = -12f,
        )
        filter.process(buf)

        val refLowHigh = lowBandEnergy(reference) / highBandEnergy(reference)
        val filtLowHigh = lowBandEnergy(buf) / highBandEnergy(buf)

        assertTrue(
            "High-shelf cut should increase low/high ratio: " +
                "ref=$refLowHigh, filtered=$filtLowHigh",
            filtLowHigh > refLowHigh * 1.5,
        )
    }

    /**
     * T12b — passthrough filter does not alter the signal.
     */
    @Test
    fun passthrough_does_not_alter_signal() {
        val buf = whiteNoise(4096)
        val reference = buf.copyOf()

        Biquad.passthrough().process(buf)

        for (i in buf.indices) {
            assertTrue(
                "Passthrough should not change sample[$i]: " +
                    "expected=${reference[i]}, got=${buf[i]}",
                kotlin.math.abs(buf[i] - reference[i]) < 1e-6f,
            )
        }
    }

    /**
     * T13 — process() is allocation-free in the hot path.
     */
    @Test
    fun process_is_allocation_free() {
        val filter = Biquad.lowShelf(44_100, 500f, 6f)
        val buf = FloatArray(1024)
        val src = NoiseSource(seed = 0x1234L)

        // Warm up.
        repeat(2_000) {
            src.fill(buf)
            filter.process(buf)
        }

        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        repeat(10_000) {
            src.fill(buf)
            filter.process(buf)
        }

        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val delta = after - before

        assertTrue(
            "Heap grew by $delta bytes during 10k allocation-free process() calls",
            delta < 256 * 1024,
        )
    }

    /**
     * T13b — filter output remains bounded for bounded input.
     * A stable biquad should not produce NaN, Inf, or unbounded growth.
     */
    @Test
    fun output_remains_bounded() {
        val filter = Biquad.lowShelf(44_100, 500f, 12f)
        val buf = whiteNoise(44_100 * 5) // 5 seconds
        filter.process(buf)

        for (i in buf.indices) {
            val v = buf[i]
            assertTrue(
                "sample[$i]=$v is NaN or Inf",
                v.isFinite(),
            )
            assertTrue(
                "sample[$i]=$v exceeds ±10.0 (unbounded growth)",
                v in -10f..10f,
            )
        }
    }
}
