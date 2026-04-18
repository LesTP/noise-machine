package com.noisemachine.app.playback

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
 * Timer countdown is delegated to [TimerController] (service scope) so it
 * survives Activity/ViewModel destruction. When no [TimerController] is
 * provided (unit tests), timer operations are no-ops and [timerState]
 * stays [TimerState.Off].
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
    private val prefs: PrefsStore? = null,
    private val timerController: TimerController? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        if (controller.isPlaying) PlaybackState.Playing else PlaybackState.Idle,
    )
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _color = MutableStateFlow(0f)
    val color: StateFlow<Float> = _color.asStateFlow()

    val timerState: StateFlow<TimerState> =
        timerController?.timerState ?: MutableStateFlow(TimerState.Off)

    /** Last selected timer duration, restored from prefs for UI pre-selection. */
    var lastTimerDurationMs: Long = 0L
        private set

    private var fadeJob: Job? = null

    init {
        prefs?.let {
            val savedColor = it.color.coerceIn(0f, 1f)
            _color.value = savedColor
            controller.setColor(savedColor)
            lastTimerDurationMs = it.timerDurationMs
        }
        timerController?.let {
            it.onTimerExpired = { onStopClicked() }
        }
    }

    fun onColorChanged(color: Float) {
        val c = color.coerceIn(0f, 1f)
        _color.value = c
        controller.setColor(c)
        prefs?.let { it.color = c }
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
                return
            }
            // If resuming from a fade-out, the gain smoother may be targeting 0.
            // Snap it back to full volume since there's no fade-in to ramp it.
            controller.snapGain(1f)
        }

        // Start timer countdown if one is pre-selected.
        if (lastTimerDurationMs > 0) {
            timerController?.startTimer(lastTimerDurationMs)
        }
    }

    fun onTimerSelected(durationMs: Long) {
        timerController?.cancelTimer()
        lastTimerDurationMs = durationMs
        prefs?.let { it.timerDurationMs = durationMs }
        val s = _state.value
        if (durationMs > 0L && (s == PlaybackState.Playing || s == PlaybackState.FadingIn)) {
            timerController?.startTimer(durationMs)
        }
    }

    fun onStopClicked() {
        // Cancel timer before the idle guard so manual stop always clears it.
        timerController?.cancelTimer()

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
        timerController?.onTimerExpired = null
        // Do NOT call controller.stop() — the service owns engine lifecycle.
        // The engine must survive ViewModel destruction for background playback.
        super.onCleared()
    }

    /**
     * Factory that wires a [PlaybackController] (bound service) into a
     * fresh [PlaybackViewModel]. The Activity provides both the controller
     * (via service binding) and the application context (for prefs).
     */
    class Factory(
        private val controller: PlaybackController,
        private val appContext: Context,
        private val timerController: TimerController? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlaybackViewModel::class.java)) {
                "Factory only creates PlaybackViewModel; got $modelClass"
            }
            return PlaybackViewModel(
                controller = controller,
                fadeInMs = DEFAULT_FADE_IN_MS,
                fadeOutMs = DEFAULT_FADE_OUT_MS,
                prefs = SharedPrefsStore(appContext),
                timerController = timerController,
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
