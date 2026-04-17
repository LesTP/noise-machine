package com.noisemachine.app.playback

import com.noisemachine.app.audio.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PlaybackViewModel] covering Phase-1 test spec T6 and the
 * T6a..T6h sub-tests added during the Step-4 phase plan
 * (see DEVPLAN.md, Phase 1 Test Spec).
 *
 * Tests use a [FakeController] in place of `AudioEngine` so the ViewModel's
 * state-machine and idempotency contracts are validated independently of the
 * audio pipeline (see DECISIONS.md D-13).
 */
class PlaybackViewModelTest {

    /**
     * Records start/stop call counts and lets a single [start] call throw on
     * demand, in support of T6h.
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
    }

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
}
