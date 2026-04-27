package com.movingfingerstudios.noisemachine.playback

/**
 * UI-facing playback state.
 *
 * Phase 3 adds [FadingIn] and [FadingOut] for smooth volume transitions.
 * When fade durations are zero, only [Idle] and [Playing] are used
 * (backward compatible with Phase 1/2 behavior).
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object FadingIn : PlaybackState
    data object Playing : PlaybackState
    data object FadingOut : PlaybackState
}
