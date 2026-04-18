package com.noisemachine.app.playback

/**
 * Abstraction over persistent storage for user preferences (D-23).
 * Production: [SharedPrefsStore]. Tests: in-memory fake.
 */
interface PrefsStore {
    var color: Float
    var timerDurationMs: Long
    var texture: Float
    var stereoEnabled: Boolean
    var microDriftDepth: Float
    var fadeInMs: Long
    var fadeOutMs: Long
}
