package com.noisemachine.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MicroDrift] — slow LFO producing Color offset.
 *
 * T47: depth=0 → offset is always 0.0.
 * T48: depth>0 → offset is non-constant after enough samples.
 */
class MicroDriftTest {

    private val sampleRate = 44_100

    // ── T47: depth=0 → offset always 0 ─────────────────────────────

    @Test
    fun `T47 — depth 0 produces zero offset`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(0f)

        // Advance many blocks (simulate ~60 s of audio)
        val framesPerBlock = 1024
        val blocks = (sampleRate * 60) / framesPerBlock

        for (i in 0 until blocks) {
            val offset = drift.nextBlock(framesPerBlock)
            assertEquals(
                "Offset must be 0.0 at depth=0 (block $i)",
                0f,
                offset,
                1e-7f,
            )
        }
    }

    @Test
    fun `T47b — depth 0 after being nonzero returns to zero`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(1f)

        // Let LFO run for a bit
        repeat(1000) { drift.nextBlock(1024) }

        // Set depth back to 0 and let smoother settle
        drift.setDepth(0f)
        repeat(500) { drift.nextBlock(1024) }

        // Should be effectively zero now
        val offset = drift.nextBlock(1024)
        assertEquals(
            "Offset must converge to 0 after depth set to 0",
            0f,
            offset,
            1e-5f,
        )
    }

    // ── T48: depth>0 → offset drifts over time ─────────────────────

    @Test
    fun `T48 — depth 1 produces non-constant offset`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(1f)

        // Let the depth smoother settle
        repeat(100) { drift.nextBlock(1024) }

        // Collect offsets over ~30 s of audio
        val framesPerBlock = 1024
        val blocks = (sampleRate * 30) / framesPerBlock
        val offsets = mutableSetOf<Float>()

        for (i in 0 until blocks) {
            val offset = drift.nextBlock(framesPerBlock)
            // Quantize to 4 decimal places to avoid floating-point noise
            offsets.add((offset * 10000).toInt() / 10000f)
        }

        assertTrue(
            "At depth=1, offset should have multiple distinct values over 30s (got ${offsets.size})",
            offsets.size > 5,
        )
    }

    @Test
    fun `T48b — offset is bounded by MAX_OFFSET`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(1f)

        // Let smoother settle
        repeat(100) { drift.nextBlock(1024) }

        val framesPerBlock = 1024
        val blocks = (sampleRate * 60) / framesPerBlock

        for (i in 0 until blocks) {
            val offset = drift.nextBlock(framesPerBlock)
            assertTrue(
                "Offset $offset exceeds ±MAX_OFFSET at block $i",
                offset >= -MicroDrift.MAX_OFFSET - 1e-6f &&
                    offset <= MicroDrift.MAX_OFFSET + 1e-6f,
            )
        }
    }

    @Test
    fun `T48c — offset changes sign over a full period`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(1f)

        // Let smoother settle
        repeat(200) { drift.nextBlock(1024) }

        // Collect over one full period (~20 s at 0.05 Hz)
        val framesPerBlock = 1024
        val blocks = (sampleRate * 25) / framesPerBlock
        var hasPositive = false
        var hasNegative = false

        for (i in 0 until blocks) {
            val offset = drift.nextBlock(framesPerBlock)
            if (offset > 0.001f) hasPositive = true
            if (offset < -0.001f) hasNegative = true
        }

        assertTrue("Offset should have positive values in a full period", hasPositive)
        assertTrue("Offset should have negative values in a full period", hasNegative)
    }

    @Test
    fun `T48d — intermediate depth scales offset proportionally`() {
        val driftFull = MicroDrift(sampleRate)
        val driftHalf = MicroDrift(sampleRate)
        driftFull.setDepth(1f)
        driftHalf.setDepth(0.5f)

        // Let smoothers settle
        repeat(200) { driftFull.nextBlock(1024); driftHalf.nextBlock(1024) }

        // Reset phase alignment
        driftFull.reset()
        driftHalf.reset()
        driftFull.setDepth(1f)
        driftHalf.setDepth(0.5f)

        // Let smoothers settle again after reset
        repeat(200) { driftFull.nextBlock(1024); driftHalf.nextBlock(1024) }

        // Collect peak absolute offsets
        var peakFull = 0f
        var peakHalf = 0f
        val blocks = (sampleRate * 25) / 1024

        for (i in 0 until blocks) {
            val oFull = kotlin.math.abs(driftFull.nextBlock(1024))
            val oHalf = kotlin.math.abs(driftHalf.nextBlock(1024))
            if (oFull > peakFull) peakFull = oFull
            if (oHalf > peakHalf) peakHalf = oHalf
        }

        // Half depth should produce roughly half the peak offset
        assertTrue("Peak at depth=0.5 ($peakHalf) should be < peak at depth=1.0 ($peakFull)", peakHalf < peakFull)
        val ratio = peakHalf / peakFull
        assertTrue(
            "Ratio of half/full peaks ($ratio) should be roughly 0.5 (±0.15)",
            ratio in 0.35f..0.65f,
        )
    }

    // ── Reset ───────────────────────────────────────────────────────

    @Test
    fun `reset clears state`() {
        val drift = MicroDrift(sampleRate)
        drift.setDepth(1f)

        // Run for a while
        repeat(1000) { drift.nextBlock(1024) }

        drift.reset()

        // After reset, depth is 0 and phase is 0
        val offset = drift.nextBlock(1024)
        assertEquals("Offset after reset should be 0", 0f, offset, 1e-7f)
    }
}
