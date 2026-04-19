package com.noisemachine.app.playback

import com.noisemachine.app.audio.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PlaybackViewModel] covering Phase-1 test spec T6/T6a..T6h,
 * Phase-3 fade tests T23/T24/T25, and Phase-4 timer delegation T26/T27/T28.
 *
 * Tests use a [FakeController] in place of `AudioEngine` so the ViewModel's
 * state-machine and idempotency contracts are validated independently of the
 * audio pipeline (see DECISIONS.md D-13).
 *
 * Phase-1 tests use default `fadeInMs=0, fadeOutMs=0` so behavior is immediate
 * and unchanged. Phase-3 fade tests use non-zero durations with [StandardTestDispatcher].
 *
 * Phase-4 timer tests use [FakeTimerController] to verify the VM delegates
 * timer operations correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Records start/stop/gain call counts and lets a single [start] call
     * throw on demand, in support of T6h.
     */
    private class FakeController : PlaybackController {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        @Volatile var failNextStart: Boolean = false
        @Volatile private var playing: Boolean = false

        override val isPlaying: Boolean get() = playing

        override fun start() {
            startCalls.incrementAndGet()
            if (failNextStart) {
                failNextStart = false
                throw IllegalStateException("simulated start failure")
            }
            playing = true
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            playing = false
        }

        @Volatile var lastColor: Float = 0f
        override fun setColor(color: Float) {
            lastColor = color
        }

        @Volatile var lastGain: Float = 1f
        val gainHistory = mutableListOf<Float>()
        override fun setGain(gain: Float) {
            lastGain = gain
            synchronized(gainHistory) { gainHistory.add(gain) }
        }

        @Volatile var lastSnapGain: Float = 1f
        override fun snapGain(gain: Float) {
            lastSnapGain = gain
        }

        override fun setTexture(texture: Float) {}
        override fun setStereoWidth(width: Float) {}
        override fun setMicroDriftDepth(depth: Float) {}
        override fun setFadeTime(seconds: Float) {}
    }

    /**
     * Fake [TimerController] that records calls and exposes a mutable
     * [timerState] for test control.
     */
    private class FakeTimerController : TimerController {
        private val _timerState = MutableStateFlow<TimerState>(TimerState.Off)
        override val timerState: StateFlow<TimerState> = _timerState

        override var onTimerExpired: (() -> Unit)? = null

        val startTimerCalls = mutableListOf<Long>()
        var cancelTimerCalls = 0
            private set

        override fun startTimer(durationMs: Long) {
            startTimerCalls.add(durationMs)
            _timerState.value = TimerState.Armed(durationMs)
        }

        override fun cancelTimer() {
            cancelTimerCalls++
            _timerState.value = TimerState.Off
        }

        /** Simulate timer reaching zero — invoke the callback. */
        fun simulateExpiry() {
            _timerState.value = TimerState.Off
            onTimerExpired?.invoke()
        }
    }

    // ── Phase 1 tests (fadeInMs=0, fadeOutMs=0) ──────────────────────

    /** T6a — initial state is Idle and the controller is untouched. */
    @Test
    fun initial_state_is_idle_and_does_not_touch_controller() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(0, controller.startCalls.get())
        assertEquals(0, controller.stopCalls.get())
    }

    /** T6 + T6b — Idle → Playing on play; controller.start called exactly once. */
    @Test
    fun on_play_clicked_starts_controller_and_transitions_to_playing() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        vm.onPlayClicked()

        assertSame(PlaybackState.Playing, vm.state.value)
        assertEquals(1, controller.startCalls.get())
        assertEquals(0, controller.stopCalls.get())
    }

    /** T6 + T6c — Playing → Idle on stop; controller.stop called exactly once. */
    @Test
    fun on_stop_clicked_stops_controller_and_transitions_to_idle() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)
        vm.onPlayClicked() // → Playing

        vm.onStopClicked()

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(1, controller.startCalls.get())
        assertEquals(1, controller.stopCalls.get())
    }

    /** T6d — onPlayClicked while already Playing is a no-op. */
    @Test
    fun on_play_clicked_while_playing_is_no_op() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)
        vm.onPlayClicked()

        vm.onPlayClicked()
        vm.onPlayClicked()

        assertSame(PlaybackState.Playing, vm.state.value)
        assertEquals("controller.start must be called only once", 1, controller.startCalls.get())
    }

    /** T6e — onStopClicked while already Idle is a no-op. */
    @Test
    fun on_stop_clicked_while_idle_is_no_op() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        vm.onStopClicked()
        vm.onStopClicked()

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals("controller.stop must not be called from Idle", 0, controller.stopCalls.get())
    }

    /** T6f — onCleared() does NOT stop the controller (service owns lifecycle). */
    @Test
    fun on_cleared_does_not_stop_controller_when_playing() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)
        vm.onPlayClicked() // → Playing

        vm.onCleared()

        // Service owns the engine lifecycle — VM must not stop it.
        assertEquals(0, controller.stopCalls.get())
    }

    /** T6f' — onCleared() while Idle is a no-op (does not call stop). */
    @Test
    fun on_cleared_does_not_stop_when_idle() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        vm.onCleared()

        assertEquals(0, controller.stopCalls.get())
    }

    // ── Phase 4 delegation test ──────────────────────────────────────

    /**
     * T34 — ViewModel delegates play/stop to bound service (PlaybackController).
     *
     * Verifies that onPlayClicked() calls controller.start() and
     * onStopClicked() calls controller.stop(), confirming the delegation
     * pattern works with any PlaybackController (including a bound service).
     */
    @Test
    fun t34_viewmodel_delegates_play_stop_to_controller() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        vm.onPlayClicked()
        assertEquals("start must be delegated", 1, controller.startCalls.get())
        assertSame(PlaybackState.Playing, vm.state.value)

        vm.onStopClicked()
        assertEquals("stop must be delegated", 1, controller.stopCalls.get())
        assertSame(PlaybackState.Idle, vm.state.value)
    }

    /** T6g — VM-layer mirror of T5: 20× rapid toggle keeps state in sync. */
    @Test
    fun rapid_toggle_keeps_state_and_controller_in_sync() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        repeat(20) {
            vm.onPlayClicked()
            vm.onStopClicked()
        }

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(20, controller.startCalls.get())
        assertEquals(20, controller.stopCalls.get())
    }

    /** T6h — controller.start failure does not leave the VM in Playing. */
    @Test
    fun controller_start_failure_reverts_state_to_idle() {
        val controller = FakeController().apply { failNextStart = true }
        val vm = PlaybackViewModel(controller)

        vm.onPlayClicked() // throws inside controller.start

        assertSame("VM must not be left in Playing after start() failure", PlaybackState.Idle, vm.state.value)
        assertEquals("start was attempted exactly once", 1, controller.startCalls.get())

        // After the failure, a subsequent successful play must still work.
        vm.onPlayClicked()
        assertSame(PlaybackState.Playing, vm.state.value)
        assertEquals(2, controller.startCalls.get())
    }

    // ── Phase 3 fade tests ──────────────────────────────────────────

    /**
     * T23 — fade-in state sequence: onPlayClicked() → FadingIn → Playing.
     *
     * Verifies: state transitions to FadingIn immediately, controller.start
     * is called, gain is snapped to 0 then set to 1, and after fadeInMs
     * the state transitions to Playing.
     */
    @Test
    fun fade_in_state_sequence() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller, fadeInMs = 500, fadeOutMs = 0)

        vm.onPlayClicked()

        // Immediately after click: FadingIn, engine started, gain snapped to 0 then set to 1.
        assertSame(PlaybackState.FadingIn, vm.state.value)
        assertEquals(1, controller.startCalls.get())
        assertEquals(0f, controller.lastSnapGain, 0.001f)
        assertEquals(1f, controller.lastGain, 0.001f)

        // Advance past the fade duration and run the pending continuation.
        advanceTimeBy(500)
        runCurrent()

        assertSame(PlaybackState.Playing, vm.state.value)
    }

    /**
     * T24 — fade-out state sequence: onStopClicked() while Playing → FadingOut → Idle.
     *
     * Verifies: state transitions to FadingOut immediately, gain set to 0,
     * controller.stop NOT called yet, and after fadeOutMs the state is Idle
     * and controller.stop has been called.
     */
    @Test
    fun fade_out_state_sequence() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 500)

        vm.onPlayClicked() // → Playing (immediate, no fade-in)
        assertSame(PlaybackState.Playing, vm.state.value)

        vm.onStopClicked()

        // Immediately after stop: FadingOut, gain ramping to 0, engine still running.
        assertSame(PlaybackState.FadingOut, vm.state.value)
        assertEquals(0f, controller.lastGain, 0.001f)
        assertEquals(0, controller.stopCalls.get())

        // Advance past the fade duration and run the pending continuation.
        advanceTimeBy(500)
        runCurrent()

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(1, controller.stopCalls.get())
    }

    /**
     * T25 — fade-out completion: controller.stop() called only after delay.
     *
     * Verifies that stop is not called prematurely at the halfway point,
     * and is called exactly once after the full fade duration.
     */
    @Test
    fun fade_out_stop_only_after_full_delay() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 1000)

        vm.onPlayClicked()
        vm.onStopClicked()

        // Half the fade: still fading out, stop not called.
        advanceTimeBy(500)
        runCurrent()
        assertSame(PlaybackState.FadingOut, vm.state.value)
        assertEquals(0, controller.stopCalls.get())

        // Rest of the fade: stop called, state is Idle.
        advanceTimeBy(500)
        runCurrent()
        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(1, controller.stopCalls.get())
    }

    /**
     * T25b — Play during fade-out (no fade-in) restores gain.
     *
     * Verifies that pressing play while fading out with fadeInMs=0
     * snaps gain back to 1.0 so audio is immediately audible.
     */
    @Test
    fun play_during_fade_out_without_fade_in_restores_gain() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 500)

        vm.onPlayClicked()
        assertSame(PlaybackState.Playing, vm.state.value)

        vm.onStopClicked()
        assertSame(PlaybackState.FadingOut, vm.state.value)
        assertEquals(0f, controller.lastGain, 0.001f)

        // Play again before fade-out completes.
        vm.onPlayClicked()
        assertSame(PlaybackState.Playing, vm.state.value)
        assertEquals(1f, controller.lastSnapGain, 0.001f)
    }

    // ── Phase 4 timer delegation tests ──────────────────────────────

    /**
     * T26 — Timer pre-selected while idle delegates startTimer on play.
     *
     * Verifies that onTimerSelected while idle stores the duration,
     * and onPlayClicked calls timerController.startTimer() with that duration.
     * Timer state is observed from the controller.
     */
    @Test
    fun t26_timer_preselected_delegates_start_on_play() = runTest(testDispatcher) {
        val controller = FakeController()
        val timerCtrl = FakeTimerController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 0, timerController = timerCtrl)

        // Select timer while idle — no startTimer call yet.
        vm.onTimerSelected(60_000)
        runCurrent()
        assertEquals(60_000L, vm.lastTimerDurationMs)
        assertEquals(0, timerCtrl.startTimerCalls.size)

        // Start playback — timer delegated.
        vm.onPlayClicked()
        runCurrent()
        assertEquals(1, timerCtrl.startTimerCalls.size)
        assertEquals(60_000L, timerCtrl.startTimerCalls[0])

        // Timer state flows from controller.
        assertEquals(TimerState.Armed(60_000), vm.timerState.value)
    }

    /**
     * T27 — Timer expiry triggers onStopClicked (fade-out).
     *
     * Verifies that the onTimerExpired callback set by the VM invokes
     * onStopClicked(), triggering fade-out.
     */
    @Test
    fun t27_timer_expiry_triggers_stop() = runTest(testDispatcher) {
        val controller = FakeController()
        val timerCtrl = FakeTimerController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 500, timerController = timerCtrl)

        vm.onPlayClicked()
        assertSame(PlaybackState.Playing, vm.state.value)

        // Simulate timer expiry via the callback.
        timerCtrl.simulateExpiry()

        // onStopClicked was called → fade-out started.
        assertSame(PlaybackState.FadingOut, vm.state.value)

        // Let fade complete.
        advanceTimeBy(500)
        runCurrent()
        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(1, controller.stopCalls.get())
    }

    /**
     * T28 — Manual stop delegates cancelTimer to controller.
     *
     * Verifies that onStopClicked() calls timerController.cancelTimer().
     */
    @Test
    fun t28_manual_stop_cancels_timer() = runTest(testDispatcher) {
        val controller = FakeController()
        val timerCtrl = FakeTimerController()
        val vm = PlaybackViewModel(controller, fadeInMs = 0, fadeOutMs = 0, timerController = timerCtrl)

        vm.onPlayClicked()
        assertSame(PlaybackState.Playing, vm.state.value)

        vm.onTimerSelected(60_000)
        runCurrent()
        assertEquals(TimerState.Armed(60_000), vm.timerState.value)

        vm.onStopClicked()

        // cancelTimer must have been called (once from onTimerSelected's cancel + once from onStopClicked).
        assertTrue("cancelTimer must be called on stop", timerCtrl.cancelTimerCalls >= 1)
        assertSame(TimerState.Off, vm.timerState.value)
        assertSame(PlaybackState.Idle, vm.state.value)
    }

    /** onCleared nulls out the onTimerExpired callback. */
    @Test
    fun on_cleared_nulls_timer_expired_callback() {
        val controller = FakeController()
        val timerCtrl = FakeTimerController()
        val vm = PlaybackViewModel(controller, timerController = timerCtrl)

        // init sets the callback.
        assertNotNull(timerCtrl.onTimerExpired)

        vm.onCleared()

        assertNull("onTimerExpired must be null after onCleared", timerCtrl.onTimerExpired)
    }

    /** When controller.isPlaying is true at init, state starts as Playing. */
    @Test
    fun init_state_syncs_to_playing_when_controller_is_playing() {
        val controller = FakeController()
        controller.start() // make it playing
        val startsBefore = controller.startCalls.get()

        val vm = PlaybackViewModel(controller)

        assertSame(PlaybackState.Playing, vm.state.value)
        // Must NOT call start again — engine is already running.
        assertEquals(startsBefore, controller.startCalls.get())
    }

    // ── Phase 3 persistence tests ───────────────────────────────────

    private class FakePrefsStore(
        override var color: Float = 0f,
        override var timerDurationMs: Long = 0L,
        override var texture: Float = 0f,
        override var stereoWidth: Float = 0f,
        override var microDriftDepth: Float = 0f,
        override var fadeInMs: Long = 0L,
        override var fadeOutMs: Long = 0L,
    ) : PrefsStore

    /**
     * T29 — Saved Color restored on fresh ViewModel construction.
     *
     * Verifies that a non-zero color from prefs is applied to both the
     * StateFlow and the controller on init.
     */
    @Test
    fun saved_color_restored_on_construction() {
        val controller = FakeController()
        val prefs = FakePrefsStore(color = 0.7f)
        val vm = PlaybackViewModel(controller, prefs = prefs)

        assertEquals(0.7f, vm.color.value, 0.001f)
        assertEquals(0.7f, controller.lastColor, 0.001f)
    }

    /**
     * T30 — Saved timer duration restored on fresh ViewModel construction.
     *
     * Verifies that lastTimerDurationMs reflects the persisted value and
     * the timer is NOT auto-armed (still Off).
     */
    @Test
    fun saved_timer_duration_restored_on_construction() {
        val controller = FakeController()
        val prefs = FakePrefsStore(timerDurationMs = 1_800_000L)
        val vm = PlaybackViewModel(controller, prefs = prefs)

        assertEquals(1_800_000L, vm.lastTimerDurationMs)
        assertSame(TimerState.Off, vm.timerState.value)
    }
}
