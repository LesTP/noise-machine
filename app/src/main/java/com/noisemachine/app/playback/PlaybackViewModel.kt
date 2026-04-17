package com.noisemachine.app.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.noisemachine.app.audio.AudioEngine
import com.noisemachine.app.audio.AudioTrackSink
import com.noisemachine.app.audio.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the main screen. Owns the [PlaybackController] for the
 * lifetime of the screen and exposes a [StateFlow] of [PlaybackState] for
 * Compose to collect.
 *
 * Threading: UI events arrive on the main thread; controller calls
 * (`start()` / `stop()`) are themselves thread-safe (`AudioEngine` uses a
 * `ReentrantLock`). State writes are synchronous and happen on whatever
 * thread invoked the event handler — fine because [MutableStateFlow] is
 * thread-safe.
 *
 * Error policy (Phase 1): controller failures revert state to [PlaybackState.Idle]
 * and are swallowed. A richer event/error surface for the UI lands in
 * Phase 3 (productization).
 */
class PlaybackViewModel(
    private val controller: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _color = MutableStateFlow(0f)
    val color: StateFlow<Float> = _color.asStateFlow()

    fun onColorChanged(color: Float) {
        val c = color.coerceIn(0f, 1f)
        _color.value = c
        controller.setColor(c)
    }

    fun onPlayClicked() {
        if (_state.value == PlaybackState.Playing) return
        try {
            controller.start()
            _state.value = PlaybackState.Playing
        } catch (_: Throwable) {
            // Best-effort: ensure state stays/returns to Idle so the UI can recover.
            _state.value = PlaybackState.Idle
        }
    }

    fun onStopClicked() {
        if (_state.value == PlaybackState.Idle) return
        try {
            controller.stop()
        } catch (_: Throwable) {
            // Stop failures are non-recoverable for this session, but the user-
            // facing state must still settle to Idle so they can try again.
        }
        _state.value = PlaybackState.Idle
    }

    public override fun onCleared() {
        // Prevent the audio render thread from outliving the ViewModel.
        if (_state.value == PlaybackState.Playing) {
            try {
                controller.stop()
            } catch (_: Throwable) {
                // Same rationale as onStopClicked().
            }
            _state.value = PlaybackState.Idle
        }
    }

    /**
     * Default `ViewModelProvider.Factory` that wires the production
     * [AudioEngine] (with [AudioTrackSink]) into a fresh [PlaybackViewModel].
     */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(PlaybackViewModel::class.java)) {
                "Factory only creates PlaybackViewModel; got $modelClass"
            }
            val engine = AudioEngine(sinkFactory = { AudioTrackSink() })
            return PlaybackViewModel(engine) as T
        }
    }
}
