package com.noisemachine.app.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.noisemachine.app.audio.AudioEngine
import com.noisemachine.app.audio.AudioTrackSink
import com.noisemachine.app.audio.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the main screen. Owns the [PlaybackController] for the
 * lifetime of the screen and exposes a [StateFlow] of [PlaybackState] for
 * Compose to collect.
 *
 * Fade behavior is controlled by [fadeInMs] and [fadeOutMs]:
 * - When 0, play/stop are immediate (Phase 1/2 backward compat).
 * - When > 0, the ViewModel orchestrates gain ramps via [PlaybackController.setGain]
 *   and transitions through [PlaybackState.FadingIn] / [PlaybackState.FadingOut].
 *
 * Threading: UI events arrive on the main thread; controller calls
 * (`start()` / `stop()`) are themselves thread-safe (`AudioEngine` uses a
 * `ReentrantLock`). State writes are synchronous or from `viewModelScope`
 * (Main dispatcher) — fine because [MutableStateFlow] is thread-safe.
 */
class PlaybackViewModel(
    private val controller: PlaybackController,
    private val fadeInMs: Long = 0L,
    private val fadeOutMs: Long = 0L,
) : ViewModel() {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _color = MutableStateFlow(0f)
    val color: StateFlow<Float> = _color.asStateFlow()

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Off)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private var fadeJob: Job? = null
    private var timerJob: Job? = null

    fun onColorChanged(color: Float) {
        val c = color.coerceIn(0f, 1f)
        _color.value = c
        controller.setColor(c)
    }

    fun onPlayClicked() {
        val current = _state.value
        if (current == PlaybackState.Playing || current == PlaybackState.FadingIn) return

        // Cancel any active fade-out.
        fadeJob?.cancel()
        fadeJob = null

        if (fadeInMs > 0) {
            _state.value = PlaybackState.FadingIn
            try {
                controller.snapGain(0f)
                controller.start()
                controller.setGain(1f)
            } catch (_: Throwable) {
                _state.value = PlaybackState.Idle
                return
            }
            fadeJob = viewModelScope.launch {
                delay(fadeInMs)
                _state.value = PlaybackState.Playing
            }
        } else {
            try {
                controller.start()
                _state.value = PlaybackState.Playing
            } catch (_: Throwable) {
                _state.value = PlaybackState.Idle
            }
        }
    }

    fun onTimerSelected(durationMs: Long) {
        timerJob?.cancel()
        _timerState.value = TimerState.Armed(durationMs)
        timerJob = viewModelScope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _timerState.value = TimerState.Armed(remaining)
            }
            _timerState.value = TimerState.Off
            onStopClicked()
        }
    }

    fun onStopClicked() {
        // Cancel timer before the idle guard so manual stop always clears it.
        timerJob?.cancel()
        timerJob = null
        _timerState.value = TimerState.Off

        val current = _state.value
        if (current == PlaybackState.Idle) return

        // Cancel any active fade-in.
        fadeJob?.cancel()
        fadeJob = null

        if (fadeOutMs > 0 && current != PlaybackState.FadingOut) {
            _state.value = PlaybackState.FadingOut
            controller.setGain(0f)
            fadeJob = viewModelScope.launch {
                delay(fadeOutMs)
                try {
                    controller.stop()
                } catch (_: Throwable) {
                    // Stop failures are non-recoverable.
                }
                _state.value = PlaybackState.Idle
            }
        } else {
            try {
                controller.stop()
            } catch (_: Throwable) {
                // Stop failures are non-recoverable.
            }
            _state.value = PlaybackState.Idle
        }
    }

    public override fun onCleared() {
        fadeJob?.cancel()
        fadeJob = null
        timerJob?.cancel()
        timerJob = null
        val current = _state.value
        if (current != PlaybackState.Idle) {
            try {
                controller.stop()
            } catch (_: Throwable) {
                // Best-effort cleanup.
            }
            _state.value = PlaybackState.Idle
        }
        super.onCleared()
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
            return PlaybackViewModel(
                controller = engine,
                fadeInMs = DEFAULT_FADE_IN_MS,
                fadeOutMs = DEFAULT_FADE_OUT_MS,
            ) as T
        }
    }

    companion object {
        /** Default fade-in duration (D-25). */
        const val DEFAULT_FADE_IN_MS = 2_000L
        /** Default fade-out duration (D-25). */
        const val DEFAULT_FADE_OUT_MS = 5_000L
    }
}
