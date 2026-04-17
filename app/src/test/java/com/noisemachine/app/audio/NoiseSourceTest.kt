package com.noisemachine.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NoiseSource] covering Phase-1 test spec T1, T2, T3
 * (see DEVPLAN.md, Phase 1 Test Spec).
 */
class NoiseSourceTest {

    /** T1 — statistical properties on a 10k-sample buffer. */
    @Test
    fun statistical_properties_of_10k_buffer() {
        val src = NoiseSource(seed = 0xC0FFEEL)
        val buf = FloatArray(10_000)
        src.fill(buf)

        var sum = 0.0
        for (v in buf) sum += v
        val mean = sum / buf.size

        var sqSum = 0.0
        for (v in buf) {
            val d = v - mean
            sqSum += d * d
        }
        val variance = sqSum / buf.size

        // Uniform on [-1, 1] has theoretical mean 0 and variance 1/3 ≈ 0.3333.
        assertTrue("mean=$mean not in [-0.05, 0.05]", mean in -0.05..0.05)
        assertTrue("variance=$variance not in [0.30, 0.36]", variance in 0.30..0.36)
    }

    /**
     * T2 — fill() is allocation-free in the hot path.
     *
     * Pure-JVM heap-delta proxy: if fill() allocated even a small object per
     * sample, generating ~10M samples would balloon the heap by tens of MB,
     * far exceeding the slack threshold below.
     */
    @Test
    fun fill_is_allocation_free_in_hot_loop() {
        val src = NoiseSource(seed = 0x1234L)
        val buf = FloatArray(1024)

        // Warm up: prime JIT and any one-time class-init allocations.
        repeat(2_000) { src.fill(buf) }

        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        // ~10M samples generated in the measured window.
        repeat(10_000) { src.fill(buf) }

        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val delta = after - before

        // Per-sample allocations would be measured in MB. Allow 256 KB of
        // slack for unrelated JVM bookkeeping unrelated to the SUT.
        assertTrue(
            "Heap grew by $delta bytes during 10k allocation-free fills",
            delta < 256 * 1024
        )
    }

    /** T3 — every produced sample lies in [-1.0, 1.0]. */
    @Test
    fun all_samples_in_unit_range() {
        val src = NoiseSource(seed = 42L)
        val buf = FloatArray(50_000)
        src.fill(buf)

        for (i in buf.indices) {
            val v = buf[i]
            if (v < -1.0f || v > 1.0f) {
                throw AssertionError("sample[$i]=$v outside [-1.0, 1.0]")
            }
        }
    }
}
