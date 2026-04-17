package com.noisemachine.app.playback

import com.noisemachine.app.audio.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PlaybackViewModel] covering Phase-1 test spec T6/T6a..T6h
 * and Phase-3 fade tests T23/T24/T25.
 *
 * Tests use a [FakeController] in place of `AudioEngine` so the ViewModel's
 * state-machine and idempotency contracts are validated independently of the
 * audio pipeline (see DECISIONS.md D-13).
 *
 * Phase-1 tests use default `fadeInMs=0, fadeOutMs=0` so behavior is immediate
 * and unchanged. Phase-3 fade tests use non-zero durations with [StandardTestDispatcher].
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

    /** T6f — onCleared() stops the controller if Playing. */
    @Test
    fun on_cleared_stops_controller_when_playing() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)
        vm.onPlayClicked() // → Playing

        vm.onCleared()

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(1, controller.stopCalls.get())
    }

    /** T6f' — onCleared() while Idle is a no-op (does not call stop). */
    @Test
    fun on_cleared_does_not_stop_when_idle() {
        val controller = FakeController()
        val vm = PlaybackViewModel(controller)

        vm.onCleared()

        assertSame(PlaybackState.Idle, vm.state.value)
        assertEquals(0, controller.stopCalls.get())
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
     * T25
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
}
