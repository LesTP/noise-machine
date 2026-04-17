package com.noisemachine.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ParameterSmoother] covering Phase-2 test spec T8, T9, T10
 * (see DEVPLAN.md, Phase 2 Test Spec).
 */
class ParameterSmootherTest {

    /**
     * T8 — smoother reaches target value within tolerance after sufficient samples.
     *
     * With a 50 ms time constant at 44100 Hz, 5 time constants ≈ 11025 samples
     * covers ~99.3% of the step. After that many calls to next(), the value
     * should be within 1% of the target.
     */
    @Test
    fun ramp_reaches_target() {
        val smoother = ParameterSmoother(
            initialValue = 0.0f,
            sampleRate = 44_100,
            timeSeconds = 0.05f,
        )
        smoother.setTarget(1.0f)

        // Advance 5 time constants worth of samples.
        val samples = (44_100 * 0.05f * 5).toInt() // ~11025
        var value = 0.0f
        repeat(samples) { value = smoother.next() }

        assertEquals("Should converge to target within 1%", 1.0f, value, 0.01f)
    }

    /**
     * T8b — ramp is monotonic for a step-up (no overshoot).
     */
    @Test
    fun ramp_is_monotonic_step_up() {
        val smoother = ParameterSmoother(
            initialValue = 0.0f,
            sampleRate = 44_100,
            timeSeconds = 0.05f,
        )
        smoother.setTarget(1.0f)

        var prev = 0.0f
        repeat(5_000) {
            val v = smoother.next()
            assertTrue("Value must be non-decreasing: prev=$prev, v=$v", v >= prev)
            prev = v
        }
    }

    /**
     * T8c — ramp is monotonic for a step-down (no undershoot).
     */
    @Test
    fun ramp_is_monotonic_step_down() {
        val smoother = ParameterSmoother(
            initialValue = 1.0f,
            sampleRate = 44_100,
            timeSeconds = 0.05f,
        )
        smoother.setTarget(0.0f)

        var prev = 1.0f
        repeat(5_000) {
            val v = smoother.next()
            assertTrue("Value must be non-increasing: prev=$prev, v=$v", v <= prev)
            prev = v
        }
    }

    /**
     * T9 — next() is allocation-free in the hot path.
     *
     * Same heap-delta pattern as NoiseSource T2: warm up, GC, measure,
     * run many iterations, GC, assert heap didn't grow.
     */
    @Test
    fun next_is_allocation_free() {
        val smoother = ParameterSmoother(
            initialValue = 0.0f,
            sampleRate = 44_100,
            timeSeconds = 0.05f,
        )
        smoother.setTarget(0.5f)

        // Warm up.
        repeat(2_000) { smoother.next() }

        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        repeat(1_000_000) { smoother.next() }

        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val delta = after - before

        assertTrue(
            "Heap grew by $delta bytes during 1M allocation-free next() calls",
            delta < 256 * 1024,
        )
    }

    /**
     * T10 — initial value is returned immediately without ramping.
     */
    @Test
    fun initial_value_returned_immediately() {
        val smoother = ParameterSmoother(
            initialValue = 0.75f,
            sampleRate = 44_100,
            timeSeconds = 0.05f,
        )

        // First call should return the initial value (no target has been set).
        val first = smoother.next()
        assertEquals("First next() should return initial value", 0.75f, first, 0.0001f)
    }

    /**
     * T10b — instant mode (timeSeconds=0) snaps to target immediately.
     */
    @Test
    fun instant_mode_snaps_to_target() {
        val smoother = ParameterSmoother(
            initialValue = 0.0f,
            sampleRate = 44_100,
            timeSeconds = 0.0f,
        )
        smoother.setTarget(1.0f)

        val value = smoother.next()
        assertEquals("Instant mode should snap to target", 1.0f, value, 0.0001f)
    }
}
