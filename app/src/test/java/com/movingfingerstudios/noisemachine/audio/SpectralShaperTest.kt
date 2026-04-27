package com.movingfingerstudios.noisemachine.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpectralShaper] covering Phase-2 test spec T14, T15, T16
 * (see DEVPLAN.md, Phase 2 Test Spec).
 *
 * Uses the same band-energy comparison approach as BiquadTest: feed seeded
 * white noise, measure low-band vs high-band RMS energy ratio, compare
 * across Color values.
 */
class SpectralShaperTest {

    private fun whiteNoise(size: Int, seed: Long = 0xCAFEL): FloatArray {
        val src = NoiseSource(seed)
        val buf = FloatArray(size)
        src.fill(buf)
        return buf
    }

    private fun rms(buf: FloatArray): Double {
        var sum = 0.0
        for (v in buf) sum += v.toDouble() * v.toDouble()
        return kotlin.math.sqrt(sum / buf.size)
    }

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

    private fun highBandEnergy(buf: FloatArray, windowSize: Int = 64): Double {
        val smoothed = FloatArray(buf.size)
        var sum = 0.0
        for (i in buf.indices) {
            sum += buf[i]
            if (i >= windowSize) sum -= buf[i - windowSize]
            val count = minOf(i + 1, windowSize)
            smoothed[i] = (sum / count).toFloat()
        }
        val high = FloatArray(buf.size)
        for (i in buf.indices) high[i] = buf[i] - smoothed[i]
        return rms(high)
    }

    private fun lowHighRatio(buf: FloatArray): Double =
        lowBandEnergy(buf) / highBandEnergy(buf)

    /**
     * T14 — Color=0.0 produces approximately flat (white) spectrum.
     *
     * The low/high energy ratio should be close to that of unprocessed
     * white noise (within 20% tolerance).
     */
    @Test
    fun color_zero_is_approximately_flat() {
        val reference = whiteNoise(44_100)
        val shaped = reference.copyOf()

        val shaper = SpectralShaper(sampleRate = 44_100)
        shaper.process(shaped, color = 0.0f)

        val refRatio = lowHighRatio(reference)
        val shapedRatio = lowHighRatio(shaped)

        val deviation = kotlin.math.abs(shapedRatio - refRatio) / refRatio
        assertTrue(
            "Color=0 should be near-flat: refRatio=$refRatio, shapedRatio=$shapedRatio, " +
                "deviation=${deviation * 100}%",
            deviation < 0.20,
        )
    }

    /**
     * T15 — Color=1.0 produces a strongly tilted (brown-like) spectrum.
     *
     * The low/high energy ratio should be significantly higher than
     * unprocessed white noise.
     */
    @Test
    fun color_one_is_tilted_toward_low_frequencies() {
        val reference = whiteNoise(44_100)
        val shaped = reference.copyOf()

        val shaper = SpectralShaper(sampleRate = 44_100)
        shaper.process(shaped, color = 1.0f)

        val refRatio = lowHighRatio(reference)
        val shapedRatio = lowHighRatio(shaped)

        assertTrue(
            "Color=1 should tilt spectrum toward lows: refRatio=$refRatio, " +
                "shapedRatio=$shapedRatio",
            shapedRatio > refRatio * 2.0, // at least 2× increase in low/high ratio
        )
    }

    /**
     * T15b — Color=0.5 produces an intermediate tilt.
     *
     * The ratio should be between Color=0 and Color=1.
     */
    @Test
    fun color_half_is_intermediate() {
        val seed = 0xCAFEL
        val white = whiteNoise(44_100, seed)
        val half = whiteNoise(44_100, seed)
        val full = whiteNoise(44_100, seed)

        val shaper = SpectralShaper(sampleRate = 44_100)
        shaper.process(white, color = 0.0f)

        shaper.reset()
        shaper.process(half, color = 0.5f)

        shaper.reset()
        shaper.process(full, color = 1.0f)

        val ratioWhite = lowHighRatio(white)
        val ratioHalf = lowHighRatio(half)
        val ratioFull = lowHighRatio(full)

        assertTrue(
            "Color=0.5 ratio ($ratioHalf) should be between Color=0 ($ratioWhite) " +
                "and Color=1 ($ratioFull)",
            ratioHalf > ratioWhite && ratioHalf < ratioFull,
        )
    }

    /**
     * T16 — process() is allocation-free in the hot path.
     *
     * Tests with a fixed Color value so coefficients are calculated once
     * on the first call and then the inner loop runs without allocation.
     */
    @Test
    fun process_is_allocation_free() {
        val shaper = SpectralShaper(sampleRate = 44_100)
        val buf = FloatArray(1024)
        val src = NoiseSource(seed = 0x5678L)

        // Warm up (first call triggers coefficient calculation).
        src.fill(buf)
        shaper.process(buf, color = 0.5f)
        repeat(2_000) {
            src.fill(buf)
            shaper.process(buf, color = 0.5f)
        }

        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        repeat(10_000) {
            src.fill(buf)
            shaper.process(buf, color = 0.5f)
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
     * T16b — output remains bounded across all Color values.
     */
    @Test
    fun output_bounded_across_color_range() {
        val shaper = SpectralShaper(sampleRate = 44_100)
        val src = NoiseSource(seed = 0xABCDL)
        val buf = FloatArray(4096)

        for (colorInt in 0..10) {
            val color = colorInt / 10f
            shaper.reset()
            src.fill(buf)
            shaper.process(buf, color = color)

            for (i in buf.indices) {
                val v = buf[i]
                assertTrue("Color=$color sample[$i]=$v is not finite", v.isFinite())
                assertTrue(
                    "Color=$color sample[$i]=$v exceeds ±10.0",
                    v in -10f..10f,
                )
            }
        }
    }
}
