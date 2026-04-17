package com.noisemachine.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GainSafety] covering Phase-2 test spec T17, T18
 * (see DEVPLAN.md, Phase 2 Test Spec).
 */
class GainSafetyTest {

    /**
     * T17 — output is always clamped to [-1.0, 1.0], even with extreme input.
     */
    @Test
    fun output_always_in_unit_range() {
        val gs = GainSafety(sampleRate = 44_100)

        // Create a buffer with extreme values.
        val buf = floatArrayOf(
            0f, 0.5f, -0.5f, 1.0f, -1.0f,
            5.0f, -5.0f, 100f, -100f, Float.MAX_VALUE, -Float.MAX_VALUE,
        )

        gs.process(buf, color = 0.5f)

        for (i in buf.indices) {
            assertTrue(
                "sample[$i]=${buf[i]} outside [-1,1]",
                buf[i] in -1f..1f,
            )
        }
    }

    /**
     * T17b — output is bounded across all Color values with shaped noise input.
     *
     * Runs SpectralShaper + GainSafety pipeline at various Color values.
     */
    @Test
    fun output_bounded_with_shaped_noise() {
        val src = NoiseSource(seed = 0xFACEL)
        val shaper = SpectralShaper(44_100)
        val gs = GainSafety(44_100)
        val buf = FloatArray(4096)

        for (colorInt in 0..10) {
            val color = colorInt / 10f
            shaper.reset()
            gs.reset()
            src.fill(buf)
            shaper.process(buf, color = color)
            gs.process(buf, color = color)

            for (i in buf.indices) {
                assertTrue(
                    "Color=$color sample[$i]=${buf[i]} outside [-1,1]",
                    buf[i] in -1f..1f,
                )
            }
        }
    }

    /**
     * T18 — DC offset is removed from the signal.
     *
     * Feeds a constant DC signal (all samples = 0.5) through GainSafety
     * and verifies the output settles near zero.
     */
    @Test
    fun removes_dc_offset() {
        val gs = GainSafety(sampleRate = 44_100)

        // 1 second of pure DC at 0.5.
        val buf = FloatArray(44_100) { 0.5f }
        gs.process(buf, color = 0.5f)

        // After the DC blocker settles (last quarter of the buffer),
        // the output should be near zero.
        val lastQuarter = buf.size * 3 / 4
        var sum = 0.0
        for (i in lastQuarter until buf.size) {
            sum += buf[i]
        }
        val mean = sum / (buf.size - lastQuarter)

        assertTrue(
            "DC-blocked output mean=$mean should be near zero",
            kotlin.math.abs(mean) < 0.05,
        )
    }

    /**
     * T18b — gain compensation reduces energy for extreme Color values.
     *
     * White noise at Color=0 should have a gain < 1.0 (perceptual loudness
     * reduction for broadband content).
     */
    @Test
    fun gain_compensation_reduces_white_noise() {
        val gain = GainSafety.compensationGain(0f)
        assertTrue("Gain at Color=0 should be < 1.0: $gain", gain < 1f)
        assertTrue("Gain at Color=0 should be > 0: $gain", gain > 0f)
    }

    /**
     * T18c — gain compensation curve is continuous (no jumps at the
     * midpoint between the two linear segments).
     */
    @Test
    fun gain_compensation_is_continuous() {
        val at049 = GainSafety.compensationGain(0.49f)
        val at050 = GainSafety.compensationGain(0.50f)
        val at051 = GainSafety.compensationGain(0.51f)

        assertEquals(
            "Gain should be continuous at Color=0.5",
            at050, at049, 0.02f,
        )
        assertEquals(
            "Gain should be continuous at Color=0.5",
            at050, at051, 0.02f,
        )
    }

    /**
     * T18d — process is allocation-free in the hot path.
     */
    @Test
    fun process_is_allocation_free() {
        val gs = GainSafety(44_100)
        val buf = FloatArray(1024)
        val src = NoiseSource(seed = 0x9999L)

        // Warm up.
        repeat(2_000) {
            src.fill(buf)
            gs.process(buf, color = 0.5f)
        }

        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        repeat(10_000) {
            src.fill(buf)
            gs.process(buf, color = 0.5f)
        }

        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val delta = after - before

        assertTrue(
            "Heap grew by $delta bytes during 10k allocation-free process() calls",
            delta < 256 * 1024,
        )
    }
}
