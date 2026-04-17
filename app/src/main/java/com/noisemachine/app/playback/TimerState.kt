package com.noisemachine.app.playback

/**
 * Timer state for the auto-stop countdown (D-22).
 * Orthogonal to [PlaybackState] — kept as a separate [StateFlow] to avoid
 * state-explosion from a merged hierarchy.
 */
sealed interface TimerState {
    data object Off : TimerState
    data class Armed(val remainingMs: Long) : TimerState
}
