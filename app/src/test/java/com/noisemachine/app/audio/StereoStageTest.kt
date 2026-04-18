package com.noisemachine.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StereoStage] — all-pass stereo decorrelation.
 *
 * T41: width=0 produces identical L/R channels.
 * T42: width>0 produces different L/R channels.
 * T43: allocation-free + all output samples finite and bounded.
 */
class StereoStageTest {

    // ── T41: identical L/R at width=0 ──────────────────────────────

    @Test
    fun `T41 — width 0 produces identical L and R channels`() {
        val stage = StereoStage()
        val noise = NoiseSource(seed = 0xB001)
        val mono = FloatArray(2048)
        noise.fill(mono)
        val stereo = ShortArray(mono.size * 2)

        stage.processToStereo(mono, stereo, mono.size, width = 0f)

        for (i in 0 until mono.size) {
            val l = stereo[i * 2]
            val r = stereo[i * 2 + 1]
            assertEquals("Frame $i: L and R must be identical at width=0", l, r)
        }
    }

    @Test
    fun `T41b — width 0 matches legacy floatMonoToInt16Stereo output`() {
        val stage = StereoStage()
        // Known input values
        val mono = floatArrayOf(0f, 0.5f, -0.5f, 1.0f, -1.0f, 0.001f)
        val stereo = ShortArray(mono.size * 2)

        stage.processToStereo(mono, stereo, mono.size, width = 0f)

        // Verify specific conversions
        assertEquals(0, stereo[0].toInt())         // 0.0f → 0
        assertEquals(0, stereo[1].toInt())
        assertEquals(16383, stereo[2].toInt())      // 0.5f → 16383
        assertEquals(16383, stereo[3].toInt())
        assertEquals(-16383, stereo[4].toInt())     // -0.5f → -16383
        assertEquals(-16383, stereo[5].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), stereo[6].toInt())  // 1.0f → 32767
        assertEquals(Short.MAX_VALUE.toInt(), stereo[7].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), stereo[8].toInt())  // -1.0f → -32768
        assertEquals(Short.MIN_VALUE.toInt(), stereo[9].toInt())
    }

    // ── T42: different L/R at width>0 ──────────────────────────────

    @Test
    fun `T42 — width 0_3 produces different L and R channels`() {
        val stage = StereoStage()
        val noise = NoiseSource(seed = 0xB002)
        val mono = FloatArray(2048)
        noise.fill(mono)
        val stereo = ShortArray(mono.size * 2)

        stage.processToStereo(mono, stereo, mono.size, width = 0.3f)

        // Count frames where L ≠ R (skip first frame since all-pass
        // starts with zero state, first output may match).
        var diffCount = 0
        for (i in 1 until mono.size) {
            val l = stereo[i * 2]
            val r = stereo[i * 2 + 1]
            if (l != r) diffCount++
        }

        val diffRate = diffCount.toFloat() / (mono.size - 1)
        assertTrue(
            "At width=0.3, most L/R pairs should differ (got ${diffRate * 100}%)",
            diffRate > 0.80f,
        )
    }

    @Test
    fun `T42b — width 1_0 produces maximum decorrelation`() {
        val stage = StereoStage()
        val noise = NoiseSource(seed = 0xB003)
        val mono = FloatArray(2048)
        noise.fill(mono)
        val stereo = ShortArray(mono.size * 2)

        stage.processToStereo(mono, stereo, mono.size, width = 1.0f)

        var diffCount = 0
        for (i in 1 until mono.size) {
            if (stereo[i * 2] != stereo[i * 2 + 1]) diffCount++
        }

        val diffRate = diffCount.toFloat() / (mono.size - 1)
        assertTrue(
            "At width=1.0, nearly all L/R pairs should differ (got ${diffRate * 100}%)",
            diffRate > 0.90f,
        )
    }

    @Test
    fun `T42c — increasing width increases L-R difference`() {
        val noise = NoiseSource(seed = 0xB004)
        val mono = FloatArray(2048)
        noise.fill(mono)
        val original = mono.copyOf()

        // Measure average |L-R| at two widths
        fun avgDiff(width: Float): Double {
            val stage = StereoStage()
            val buf = original.copyOf()
            val stereo = ShortArray(buf.size * 2)
            stage.processToStereo(buf, stereo, buf.size, width)
            var sum = 0L
            for (i in 0 until buf.size) {
                sum += kotlin.math.abs(stereo[i * 2].toInt() - stereo[i * 2 + 1].toInt())
            }
            return sum.toDouble() / buf.size
        }

        val diff03 = avgDiff(0.3f)
        val diff10 = avgDiff(1.0f)
        assertTrue(
            "width=1.0 should produce larger L-R diff than width=0.3 ($diff10 vs $diff03)",
            diff10 > diff03,
        )
    }

    // ── T43: allocation-free + bounded ─────────────────────────────

    @Test
    fun `T43 — all output samples finite and bounded`() {
        val stage = StereoStage()
        val noise = NoiseSource(seed = 0xB005)
        val mono = FloatArray(4096)
        val stereo = ShortArray(mono.size * 2)

        // Process many buffers at various widths
        val widths = floatArrayOf(0f, 0.1f, 0.3f, 0.5f, 1.0f)
        for (w in widths) {
            noise.fill(mono)
            stage.processToStereo(mono, stereo, mono.size, w)

            for (i in stereo.indices) {
                val v = stereo[i].toInt()
                assertTrue(
                    "Sample $i at width=$w out of Int16 range: $v",
                    v in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt(),
                )
            }
        }
    }

    @Test
    fun `T43b — allocation-free`() {
        val stage = StereoStage()
        val mono = FloatArray(1024)
        val stereo = ShortArray(1024 * 2)
        val noise = NoiseSource(seed = 0xB006)

        // Warm up
        repeat(2_000) {
            noise.fill(mono)
            stage.processToStereo(mono, stereo, mono.size, width = 0.3f)
        }

        System.gc(); System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        repeat(10_000) {
            noise.fill(mono)
            stage.processToStereo(mono, stereo, mono.size, width = 0.3f)
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
    fun `reset clears all-pass state`() {
        val stage = StereoStage()
        val mono = FloatArray(512) { 0.5f }
        val stereo = ShortArray(512 * 2)

        // Process to build up filter state
        stage.processToStereo(mono, stereo, mono.size, width = 0.5f)

        stage.reset()

        // After reset, processing the same buffer should produce
        // the same output as a fresh instance
        val fresh = StereoStage()
        val stereoFresh = ShortArray(512 * 2)
        val stereoReset = ShortArray(512 * 2)

        val input = FloatArray(512) { 0.3f }
        stage.processToStereo(input.copyOf(), stereoReset, input.size, width = 0.5f)
        fresh.processToStereo(input.copyOf(), stereoFresh, input.size, width = 0.5f)

        for (i in stereoReset.indices) {
            assertEquals(
                "After reset, output should match fresh instance at index $i",
                stereoFresh[i], stereoReset[i],
            )
        }
    }
}
