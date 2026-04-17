package com.noisemachine.app.audio

/**
 * Narrow seam between [AudioEngine] and the underlying audio output (Android
 * `AudioTrack` in production; a fake in tests).
 *
 * Lifetime: each instance is single-use — `open()` once, then any number of
 * `write()` calls, then `close()`. Re-opening after close is not supported;
 * the engine creates a fresh sink per playback session via a factory.
 *
 * Implementations are not required to be thread-safe; only the engine's
 * render thread calls into a sink between `open()` and `close()`.
 */
interface AudioSink {

    /**
     * Open the sink and return the recommended number of stereo frames to
     * pass to each `write()` call. The engine will size its render buffers
     * accordingly.
     *
     * @param sampleRateHz e.g. 44100
     * @param channels e.g. 2 (stereo)
     * @return frames per write quantum (always > 0)
     */
    fun open(sampleRateHz: Int, channels: Int): Int

    /**
     * Blocking write of `frames` interleaved stereo frames. The first
     * `frames * channels` shorts of [buffer] are consumed.
     *
     * Production impls block until the audio device drains enough of its
     * internal buffer to accept the new data — this is what bounds the
     * render loop's CPU usage.
     */
    fun write(buffer: ShortArray, frames: Int)

    /**
     * Stop the sink and release all underlying resources. Called from the
     * render thread (same thread that calls [open] and [write]).
     */
    fun close()
}
