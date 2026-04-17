package com.noisemachine.app.playback

/**
 * UI-facing playback state.
 *
 * Phase 1 ships only `Idle` and `Playing` per DEVPLAN's Step 4 scope. The
 * fuller `Starting(fadeIn) | FadingOut(remainingMs) | Stopped` shape from
 * ARCHITECTURE.md is Phase 2/3 territory and is intentionally deferred.
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Playing : PlaybackState
}
