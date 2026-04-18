package com.noisemachine.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TextureShaper] — variable-rate zero-order hold.
 *
 * T38: texture=0 is passthrough (output matches input).
 * T39: texture=1 produces stepped output (adjacent samples often equal).
 * T40: allocation-free (heap delta < 256 KB after 1M calls).
 */
class TextureShaperTest {

    // ── T38: passthrough at texture=0 ──────────────────────────────

    @Test
    fun `T38 — texture 0 is passthrough`() {
        val shaper = TextureShaper()
        val input = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f)
        val expected = input.copyOf()

        shaper.process(input, input.size, texture = 0f)

        assertArrayEquals("texture=0 must not alter samples", expected, input, 1e-6f)
    }

    @Test
    fun `T38b — texture 0 passthrough with large buffer`() {
        val shaper = TextureShaper()
        val noise = NoiseSource(seed = 0xA001)
        val buf = FloatArray(4096)
        noise.fill(buf)
        val expected = buf.copyOf()

        shaper.process(buf, buf.size, texture = 0f)

        assertArrayEquals("texture=0 must not alter noise buffer", expected, buf, 1e-6f)
    }

    // ── T39: stepped output at texture=1 ───────────────────────────

    @Test
    fun `T39 — texture 1 produces stepped output`() {
        val shaper = TextureShaper()
        val noise = NoiseSource(seed = 0xA002)
        val buf = FloatArray(4096)
        noise.fill(buf)

        shaper.process(buf, buf.size, texture = 1f)

        // At texture=1, holdLength=MAX_HOLD. Count how many adjacent pairs are equal.
        var equalPairs = 0
        for (i in 1 until buf.size) {
            if (buf[i] == buf[i - 1]) equalPairs++
        }

        val repeatRate = equalPairs.toFloat() / (buf.size - 1)
        // With MAX_HOLD=6, each sample is held 6 times, so 5/6 ≈ 83% of
        // adjacent pairs should be equal. Allow generous margin.
        assertTrue(
            "At texture=1, >50% of adjacent pairs should be equal (got ${repeatRate * 100}%)",
            repeatRate > 0.50f,
        )
    }

    @Test
    fun `T39b — texture 0_5 produces intermediate stepping`() {
        val shaper = TextureShaper()
        val noise = NoiseSource(seed = 0xA003)
        val buf = FloatArray(4096)
        noise.fill(buf)

        shaper.process(buf, buf.size, texture = 0.5f)

        var equalPairs = 0
        for (i in 1 until buf.size) {
            if (buf[i] == buf[i - 1]) equalPairs++
        }

        val repeatRate = equalPairs.toFloat() / (buf.size - 1)
        // At texture=0.5, holdLength should be ~3-4, so repeat rate ~50-75%.
        // It should be higher than 0 (not passthrough) but could be less than texture=1.
        assertTrue(
            "At texture=0.5, some adjacent pairs should be equal (got ${repeatRate * 100}%)",
            repeatRate > 0.20f,
        )
    }

    @Test
    fun `T39c — all output samples are within input bounds`() {
        val shaper = TextureShaper()
        val noise = NoiseSource(seed = 0xA004)
        val buf = FloatArray(4096)
        noise.fill(buf)

        shaper.process(buf, buf.size, texture = 1f)

        for (i in buf.indices) {
            assertTrue("Sample $i out of range: ${buf[i]}", buf[i] in -1f..1f)
        }
    }

    // ── T40: allocation-free ───────────────────────────────────────

    @Test
    fun `T40 — allocation-free`() {
        val shaper = TextureShaper()
        val buf = FloatArray(1024)
        val noise = NoiseSource(seed = 0xA005)

        // Warm up
        repeat(2_000) {
            noise.fill(buf)
            shaper.process(buf, buf.size, texture = 0.7f)
        }

        System.gc(); System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        repeat(10_000) {
            noise.fill(buf)
            shaper.process(buf, buf.size, texture = 0.7f)
        }

        System.gc(); System.gc()
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val delta = after - before
        assertTrue(
            "Heap delta should be < 256 KB, got ${delta / 1024} KB",
            delta < 256 * 1024,
        )
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Test
    fun `reset clears hold state`() {
        val shaper = TextureShaper()
        val buf = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        // Process with texture=1 to establish held state
        shaper.process(buf, buf.size, texture = 1f)

        shaper.reset()

        // After reset, the next process should start fresh
        val buf2 = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f)
        shaper.process(buf2, buf2.size, texture = 1f)
        // First sample after reset should be from the new buffer
        assertEquals("First sample after reset should be new input", 10f, buf2[0], 1e-6f)
    }
}
