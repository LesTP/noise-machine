package com.noisemachine.app.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the playback render thread and orchestrates the per-buffer pipeline:
 * `NoiseSource → (Phase 2: SpectralShaper → GainSafety) → StereoStage → AudioSink`.
 *
 * Phase 1 scope: only NoiseSource → mono-to-stereo conversion → AudioSink.
 * Spectral shaping, parameter smoothing, gain safety, and stereo decorrelation
 * land in later phases.
 *
 * Lifecycle:
 * - [start] is idempotent — a second call while already playing is a no-op.
 * - [stop] joins the render thread before returning, so a subsequent [start]
 *   sees a clean slate. Both methods are guarded by the same lock, which
 *   makes rapid `start()/stop()` toggling safe (DEVPLAN test T5).
 *
 * Thread model:
 * - Render thread is a single dedicated `Thread`, recreated per session.
 * - Lifecycle methods are called from the lifecycle-control thread (UI /
 *   ViewModel / Service); they never touch the render thread's local state.
 * - Engine state visible across threads is a single `@Volatile Boolean`
 *   (`running`) — the render loop polls it once per buffer.
 *
 * Allocation discipline:
 * - Render-loop buffers (`monoBuf`, `stereoBuf`) are allocated once at the
 *   top of each session and reused for the full session.
 * - The hot loop calls only `NoiseSource.fill`, primitive math, and
 *   `AudioSink.write` — none of which allocate.
 *
 * @param sinkFactory creates a fresh [AudioSink] for each session. The engine
 *   never reuses a sink across `stop()` / `start()` because production sinks
 *   wrap single-use `AudioTrack` instances.
 * @param noiseFactory creates a fresh [NoiseSource] for each session.
 * @param sampleRateHz output sample rate (D-7: 44100).
 */
class AudioEngine(
    private val sinkFactory: () -> AudioSink,
    private val noiseFactory: () -> NoiseSource = { NoiseSource() },
    private val sampleRateHz: Int = 44_100,
) {

    private val lock = ReentrantLock()

    @Volatile
    private var running: Boolean = false

    private var renderThread: Thread? = null

    val isPlaying: Boolean
        get() = running

    /**
     * Begin playback. No-op if already playing. Returns once the render
     * thread has been launched (the first audio buffer may not yet have been
     * delivered to the sink).
     */
    fun start() = lock.withLock {
        if (running) return@withLock

        val sink = sinkFactory()
        val framesPerWrite = sink.open(sampleRateHz, CHANNELS)

        running = true
        val noise = noiseFactory()

        val thread = Thread({ renderLoop(sink, noise, framesPerWrite) }, "noise-render")
        thread.priority = Thread.MAX_PRIORITY
        renderThread = thread
        thread.start()
    }

    /**
     * Stop playback. No-op if already stopped. Blocks until the render thread
     * has drained out and the sink has been closed.
     */
    fun stop() = lock.withLock {
        if (!running) return@withLock

        running = false
        val thread = renderThread
        renderThread = null

        // Render loop will observe running=false on the next iteration boundary
        // and exit, then we close the sink. The thread joins itself.
        thread?.join(JOIN_TIMEOUT_MS)
        if (thread != null && thread.isAlive) {
            // Safety net: thread should have observed running=false within one
            // sink.write() worth of time. If not, surface it loudly.
            error("AudioEngine render thread failed to stop within ${JOIN_TIMEOUT_MS}ms")
        }
    }

    private fun renderLoop(sink: AudioSink, noise: NoiseSource, framesPerWrite: Int) {
        val monoBuf = FloatArray(framesPerWrite)
        val stereoBuf = ShortArray(framesPerWrite * CHANNELS)
        try {
            while (running) {
                noise.fill(monoBuf, framesPerWrite)
                floatMonoToInt16Stereo(monoBuf, stereoBuf, framesPerWrite)
                sink.write(stereoBuf, framesPerWrite)
            }
        } finally {
            // Always close the sink on the render thread to keep ownership
            // single-threaded between open() and close().
            sink.close()
        }
    }

    /**
     * Convert mono Float samples in `[-1.0f, 1.0f]` into interleaved stereo
     * Int16 samples in `[-32768, 32767]`. Restrained-stereo (D-5): both
     * channels carry the identical sample. Clip-safe at the rails.
     *
     * Allocation-free; no boxing.
     */
    private fun floatMonoToInt16Stereo(mono: FloatArray, stereo: ShortArray, frames: Int) {
        var i = 0
        var j = 0
        while (i < frames) {
            val v = mono[i]
            val s: Short = when {
                v >= 1.0f -> Short.MAX_VALUE
                v <= -1.0f -> Short.MIN_VALUE
                else -> (v * 32767.0f).toInt().toShort()
            }
            stereo[j] = s
            stereo[j + 1] = s
            i++
            j += 2
        }
    }

    companion object {
        private const val CHANNELS = 2
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
