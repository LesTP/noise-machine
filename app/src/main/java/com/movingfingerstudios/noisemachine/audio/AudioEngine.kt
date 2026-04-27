package com.movingfingerstudios.noisemachine.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the playback render thread and orchestrates the per-buffer pipeline:
 * ```
 * NoiseSource → SpectralShaper(color + drift) → TextureShaper → GainSafety
 *   → masterGain → StereoStage → AudioSink
 * ```
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
 * - Parameter setters write to `@Volatile` targets in smoothers / MicroDrift —
 *   safe to call from any thread without locking.
 * - Engine state visible across threads is a single `@Volatile Boolean`
 *   (`running`) — the render loop polls it once per buffer.
 *
 * Allocation discipline:
 * - Render-loop buffers (`monoBuf`, `stereoBuf`) are allocated once at the
 *   top of each session and reused for the full session.
 * - The hot loop calls only allocation-free DSP methods and `AudioSink.write`.
 *
 * @param sinkFactory creates a fresh [AudioSink] for each session.
 * @param noiseFactory creates a fresh [NoiseSource] for each session.
 * @param sampleRateHz output sample rate (D-7: 44100).
 */
class AudioEngine(
    private val sinkFactory: () -> AudioSink,
    private val noiseFactory: () -> NoiseSource = { NoiseSource() },
    private val sampleRateHz: Int = 44_100,
    private val fadeTimeSeconds: Float = 2.0f,
) : PlaybackController {

    private val lock = ReentrantLock()

    @Volatile
    private var running: Boolean = false

    private var renderThread: Thread? = null

    /** Smoothed Color parameter. UI thread writes target; render thread reads. */
    private val colorSmoother = ParameterSmoother(
        initialValue = 0f,
        sampleRate = sampleRateHz,
        timeSeconds = 0.05f,
    )

    /** Smoothed master gain for fade-in/fade-out (D-21). */
    private val gainSmoother = ParameterSmoother(
        initialValue = 1f,
        sampleRate = sampleRateHz,
        timeSeconds = fadeTimeSeconds,
    )

    /** Smoothed Texture parameter. */
    private val textureSmoother = ParameterSmoother(
        initialValue = 0f,
        sampleRate = sampleRateHz,
        timeSeconds = 0.05f,
    )

    /** Smoothed stereo width parameter. */
    private val stereoWidthSmoother = ParameterSmoother(
        initialValue = 0f,
        sampleRate = sampleRateHz,
        timeSeconds = 0.05f,
    )

    /** Micro-drift LFO — depth target is @Volatile, LFO state is render-thread only. */
    @Volatile
    private var microDrift: MicroDrift? = null

    override val isPlaying: Boolean
        get() = running

    override fun setColor(color: Float) {
        colorSmoother.setTarget(color.coerceIn(0f, 1f))
    }

    override fun setGain(gain: Float) {
        gainSmoother.setTarget(gain.coerceIn(0f, 1f))
    }

    override fun snapGain(gain: Float) {
        gainSmoother.snapTo(gain.coerceIn(0f, 1f))
    }

    override fun setTexture(texture: Float) {
        textureSmoother.setTarget(texture.coerceIn(0f, 1f))
    }

    override fun setStereoWidth(width: Float) {
        stereoWidthSmoother.setTarget(width.coerceIn(0f, 1f))
    }

    override fun setMicroDriftDepth(depth: Float) {
        microDrift?.setDepth(depth.coerceIn(0f, 1f))
    }

    override fun setFadeTime(seconds: Float) {
        gainSmoother.setTimeSeconds(seconds.coerceAtLeast(0f))
    }

    override fun start() = lock.withLock {
        if (running) return@withLock

        val sink = sinkFactory()
        val framesPerWrite = sink.open(sampleRateHz, CHANNELS)

        running = true
        val noise = noiseFactory()
        val shaper = SpectralShaper(sampleRateHz)
        val textureShaper = TextureShaper()
        val safety = GainSafety(sampleRateHz)
        val stereoStage = StereoStage()
        val drift = MicroDrift(sampleRateHz)
        microDrift = drift

        val thread = Thread(
            { renderLoop(sink, noise, shaper, textureShaper, safety, stereoStage, drift, framesPerWrite) },
            "noise-render",
        )
        thread.priority = Thread.MAX_PRIORITY
        renderThread = thread
        thread.start()
    }

    override fun stop() = lock.withLock {
        if (!running) return@withLock

        running = false
        val thread = renderThread
        renderThread = null
        microDrift = null

        thread?.join(JOIN_TIMEOUT_MS)
        if (thread != null && thread.isAlive) {
            error("AudioEngine render thread failed to stop within ${JOIN_TIMEOUT_MS}ms")
        }
    }

    private fun renderLoop(
        sink: AudioSink,
        noise: NoiseSource,
        shaper: SpectralShaper,
        textureShaper: TextureShaper,
        safety: GainSafety,
        stereoStage: StereoStage,
        drift: MicroDrift,
        framesPerWrite: Int,
    ) {
        val monoBuf = FloatArray(framesPerWrite)
        val stereoBuf = ShortArray(framesPerWrite * CHANNELS)
        try {
            while (running) {
                // Read smoothed parameters, advancing by one buffer's worth.
                val color = colorSmoother.nextBlock(framesPerWrite)
                val texture = textureSmoother.nextBlock(framesPerWrite)
                val width = stereoWidthSmoother.nextBlock(framesPerWrite)

                // MicroDrift offset added to Color for subtle tonal wandering.
                val driftOffset = drift.nextBlock(framesPerWrite)
                val effectiveColor = (color + driftOffset).coerceIn(0f, 1f)

                noise.fill(monoBuf, framesPerWrite)
                shaper.process(monoBuf, framesPerWrite, effectiveColor)
                textureShaper.process(monoBuf, framesPerWrite, texture)
                safety.process(monoBuf, framesPerWrite, effectiveColor)

                // Apply master gain (fade-in/fade-out). Post-GainSafety so
                // the hard-clip guarantee is preserved (gain ≤ 1.0).
                val gain = gainSmoother.nextBlock(framesPerWrite)
                var g = 0
                while (g < framesPerWrite) {
                    monoBuf[g] *= gain
                    g++
                }

                stereoStage.processToStereo(monoBuf, stereoBuf, framesPerWrite, width)
                sink.write(stereoBuf, framesPerWrite)
            }
        } finally {
            sink.close()
        }
    }

    companion object {
        private const val CHANNELS = 2
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
