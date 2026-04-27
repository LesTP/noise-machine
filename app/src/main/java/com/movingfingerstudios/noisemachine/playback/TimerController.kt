package com.movingfingerstudios.noisemachine.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for timer countdown ownership (D-28).
 *
 * The implementation lives in PlaybackService (service scope) so the
 * countdown survives Activity/ViewModel destruction. The ViewModel
 * observes [timerState] and delegates start/cancel through this interface.
 */
interface TimerController {
    val timerState: StateFlow<TimerState>
    var onTimerExpired: (() -> Unit)?
    fun startTimer(durationMs: Long)
    fun cancelTimer()
}
