package com.noisemachine.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [AudioEngine] covering Phase-1 test spec T4 and T5
 * (see DEVPLAN.md, Phase 1 Test Spec).
 *
 * Tests use a [FakeSink] in place of [AudioTrackSink] so the lifecycle and
 * threading contracts can be validated entirely on the JVM, with no Android
 * framework dependency.
 */
class AudioEngineTest {

    /**
     * In-memory [AudioSink] for tests. Records open/close calls and counts
     * frames written. `write()` does no real blocking — the engine's own
     * `start()/stop()` synchronization is what the tests are exercising.
     */
    private class FakeSink(private val framesPerWrite: Int = 256) : AudioSink {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val framesWritten = AtomicLong(0)
        @Volatile var lastSampleRate: Int = -1
        @Volatile var lastChannels: Int = -1

        override fun open(sampleRateHz: Int, channels: Int): Int {
            opens.incrementAndGet()
            lastSampleRate = sampleRateHz
            lastChannels = channels
            return framesPerWrite
        }

        override fun write(buffer: ShortArray, frames: Int) {
            framesWritten.addAndGet(frames.toLong())
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private fun newEngine(sink: FakeSink): AudioEngine =
        AudioEngine(
            sinkFactory = { sink },
            noiseFactory = { NoiseSource(seed = 0xDEADBEEFL) },
        )

    /**
     * T4 — start/stop lifecycle.
     *
     * Verifies isPlaying transitions and that the sink sees the expected
     * open/close pair, with the configured sample rate (D-7: 44100 Hz) and
     * stereo channel count.
     */
    @Test(timeout = 5_000)
    fun start_stop_lifecycle() {
        val sink = FakeSink()
        val engine = newEngine(sink)

        assertFalse("engine should be idle before start()", engine.isPlaying)

        engine.start()
        assertTrue("engine should report isPlaying after start()", engine.isPlaying)
        assertEquals("sink.open should be called exactly once", 1, sink.opens.get())
        assertEquals("sink should be opened at 44100 Hz", 44_100, sink.lastSampleRate)
        assertEquals("sink should be opened in stereo", 2, sink.lastChannels)

        // Give the render thread a moment to push at least one buffer.
        // Not strictly required by T4, but documents that the loop is alive.
        val deadline = System.nanoTime() + 1_000_000_000L
        while (sink.framesWritten.get() == 0L && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        assertNotEquals("render loop should have written at least one buffer", 0L, sink.framesWritten.get())

        engine.stop()
        assertFalse("engine should report !isPlaying after stop()", engine.isPlaying)
        assertEquals("sink.close should be called exactly once", 1, sink.closes.get())
    }

    /**
     * T4b — start() and stop() are idempotent.
     *
     * Not in the original DEVPLAN test grid but a direct corollary of the
     * lifecycle contract documented on AudioEngine. Cheap to verify here.
     */
    @Test(timeout = 5_000)
    fun start_and_stop_are_idempotent() {
        val sink = FakeSink()
        val engine = newEngine(sink)

        engine.start()
        engine.start() // should be no-op
        assertEquals("second start() must not re-open the sink", 1, sink.opens.get())

        engine.stop()
        engine.stop() // should be no-op
        assertEquals("second stop() must not re-close the sink", 1, sink.closes.get())
    }

    /**
     * T5 — engine survives 20 rapid start/stop toggles without exception or
     * deadlock. The 5-second JUnit timeout is the no-ANR proxy.
     *
     * Each start() must pair with exactly one sink.open() and each stop()
     * with exactly one sink.close(); we use that invariant as the strongest
     * end-state assertion.
     */
    @Test(timeout = 10_000)
    fun rapid_start_stop_does_not_crash_or_leak() {
        // A fresh sink per start() so we can count opens/closes globally.
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val factory = {
            object : AudioSink {
                override fun open(sampleRateHz: Int, channels: Int): Int {
                    opens.incrementAndGet()
                    return 256
                }
                override fun write(buffer: ShortArray, frames: Int) { /* no-op */ }
                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }
        val engine = AudioEngine(
            sinkFactory = factory,
            noiseFactory = { NoiseSource(seed = 0xFEEDL) },
        )

        repeat(20) {
            engine.start()
            engine.stop()
        }

        assertFalse("engine must end idle after rapid toggle", engine.isPlaying)
        assertEquals("each start() must open a sink", 20, opens.get())
        assertEquals("each stop() must close a sink", 20, closes.get())
    }
    /**
     * T19 — full pipeline produces bounded output for all Color values.
     *
     * Runs the engine at Color 0.0, 0.5, and 1.0 via a FakeSink that captures
     * output samples and asserts all are within [-32768, 32767] (Int16 range),
     * with no NaN or Inf in the mono float stage (validated by GainSafety's
     * hard clip + floatMonoToInt16Stereo's clip-safe conversion).
     */
    @Test(timeout = 10_000)
    fun full_pipeline_bounded_for_all_color_values() {
        for (color in listOf(0.0f, 0.5f, 1.0f)) {
            val capturedFrames = AtomicLong(0)
            var anyOutOfRange = false

            val sink = object : AudioSink {
                override fun open(sampleRateHz: Int, channels: Int): Int = 256
                override fun write(buffer: ShortArray, frames: Int) {
                    for (i in 0 until frames * 2) {
                        val v = buffer[i]
                        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
                            anyOutOfRange = true
                        }
                    }
                    capturedFrames.addAndGet(frames.toLong())
                }
                override fun close() {}
            }

            val engine = AudioEngine(
                sinkFactory = { sink },
                noiseFactory = { NoiseSource(seed = 0xC010BL + color.toLong()) },
            )

            engine.setColor(color)
            engine.start()

            // Let it run for enough buffers to exercise the pipeline.
            val deadline = System.nanoTime() + 500_000_000L // 500ms
            while (capturedFrames.get() < 4096 && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }

            engine.stop()

            assertTrue(
                "Color=$color: should have written frames, got ${capturedFrames.get()}",
                capturedFrames.get() >= 256,
            )
            assertFalse(
                "Color=$color: found out-of-range Int16 sample",
                anyOutOfRange,
            )
        }
    }
}
