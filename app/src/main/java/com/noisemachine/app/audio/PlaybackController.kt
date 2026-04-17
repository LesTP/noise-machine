package com.noisemachine.app.audio

/**
 * Lifecycle surface that the ViewModel/UI layer talks to.
 *
 * Lets `PlaybackViewModel` be unit-tested with a `FakeController` (mirrors
 * the [AudioSink] seam used by [AudioEngine] tests — see DECISIONS.md D-11
 * and D-13).
 *
 * Implementations must make `start()` and `stop()` idempotent and safe to
 * interleave from any thread; [AudioEngine] satisfies this with a
 * `ReentrantLock`.
 */
interface PlaybackController {
    val isPlaying: Boolean
    fun start()
    fun stop()
    fun setColor(color: Float)
}
