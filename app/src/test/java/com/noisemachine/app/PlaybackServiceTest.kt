package com.noisemachine.app

import android.content.Intent
import android.media.AudioManager
import com.noisemachine.app.audio.PlaybackController
import com.noisemachine.app.playback.TimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PlaybackService] covering Phase-4 test spec T32/T33/T35/T36.
 *
 * Uses Robolectric to shadow the Android Service lifecycle. The real
 * [AudioEngine] is replaced with a [FakeController] via the service's
 * [PlaybackService.controllerFactory] seam.
 *
 * Step 4.3: Timer tests (T35) inject a [StandardTestDispatcher] via
 * [PlaybackService.timerDispatcher] to control virtual time.
 *
 * Step 4.4: Notification stop action (T36), audio focus, and onTaskRemoved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceTest {

    private lateinit var fakeController: FakeController
    private lateinit var serviceController: ServiceController<PlaybackService>
    private val testDispatcher = StandardTestDispatcher()

    private class FakeController : PlaybackController {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        @Volatile private var playing: Boolean = false

        override val isPlaying: Boolean get() = playing

        override fun start() {
            startCalls.incrementAndGet()
            playing = true
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            playing = false
        }

        override fun setColor(color: Float) {}
        override fun setGain(gain: Float) {}

        val snapGainCalls = mutableListOf<Float>()
        override fun snapGain(gain: Float) {
            snapGainCalls.add(gain)
        }
    }

    @Before
    fun setUp() {
        fakeController = FakeController()
        PlaybackService.controllerFactory = { fakeController }
        PlaybackService.timerDispatcher = testDispatcher
        serviceController = Robolectric.buildService(PlaybackService::class.java)
    }

    @After
    fun tearDown() {
        PlaybackService.controllerFactory = { throw IllegalStateException("not wired") }
        PlaybackService.timerDispatcher = kotlinx.coroutines.Dispatchers.Main
    }

    // ── T32/T33 — Service lifecycle ──────────────────────────────────

    /**
     * T32 — Service starts foreground with notification.
     *
     * Calls start() via the PlaybackController interface (binder path)
     * and verifies:
     * 1. The engine is started (controller.start called).
     * 2. A foreground notification is posted.
     */
    @Test
    fun t32_start_starts_engine_and_posts_notification() {
        val service = serviceController.create().get()

        service.start()

        assertTrue("Engine must be playing after start()", service.isPlaying)
        assertEquals(1, fakeController.startCalls.get())

        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull("startForeground must have been called", notification)
    }

    /**
     * T33 — Service stops engine on stop() / onDestroy().
     *
     * Verifies two paths:
     * (a) stop() via PlaybackController stops the engine and removes foreground.
     * (b) onDestroy stops the engine as a safety net.
     */
    @Test
    fun t33_stop_stops_engine() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.stop()

        assertFalse("Engine must be stopped after stop()", service.isPlaying)
        assertEquals(1, fakeController.stopCalls.get())
    }

    @Test
    fun t33_on_destroy_stops_engine() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        serviceController.destroy()

        assertFalse("Engine must be stopped after onDestroy", fakeController.isPlaying)
        assertEquals(1, fakeController.stopCalls.get())
    }

    /** start() while already playing is idempotent. */
    @Test
    fun start_while_playing_is_no_op() {
        val service = serviceController.create().get()
        service.start()
        service.start()

        assertEquals("start must be called only once", 1, fakeController.startCalls.get())
    }

    /** stop() while not playing is a no-op. */
    @Test
    fun stop_while_not_playing_is_no_op() {
        val service = serviceController.create().get()

        service.stop()

        assertEquals("stop must not be called when not playing", 0, fakeController.stopCalls.get())
    }

    /** ACTION_STOP intent delegates to stop(). */
    @Test
    fun action_stop_intent_delegates_to_stop() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.onStartCommand(Intent(PlaybackService.ACTION_STOP), 0, 1)

        assertFalse("Engine must be stopped after ACTION_STOP", service.isPlaying)
        assertEquals(1, fakeController.stopCalls.get())
    }

    // ── T35 — Timer survives ViewModel clearing ─────────────────────

    /**
     * T35 — Timer countdown continues when ViewModel is cleared.
     *
     * Starts a timer, simulates VM destruction by nulling onTimerExpired,
     * verifies the countdown is still Armed (running in serviceScope).
     */
    @Test
    fun t35_timer_survives_viewmodel_clearing() = runTest(testDispatcher) {
        val service = serviceController.create().get()
        service.start()

        // Start a 60s timer.
        service.startTimer(60_000)
        runCurrent()
        assertEquals(TimerState.Armed(60_000), service.timerState.value)

        // Simulate VM destruction — clear the callback.
        service.onTimerExpired = null

        // Advance 2s — timer must still be ticking.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(TimerState.Armed(58_000), service.timerState.value)
    }

    /** Timer countdown ticks each second. */
    @Test
    fun timer_countdown_ticks() = runTest(testDispatcher) {
        val service = serviceController.create().get()

        service.startTimer(5_000)
        runCurrent()
        assertEquals(TimerState.Armed(5_000), service.timerState.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TimerState.Armed(4_000), service.timerState.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TimerState.Armed(3_000), service.timerState.value)
    }

    /** Timer expiry invokes onTimerExpired callback. */
    @Test
    fun timer_expiry_calls_callback() = runTest(testDispatcher) {
        val service = serviceController.create().get()
        service.start()

        var callbackInvoked = false
        service.onTimerExpired = { callbackInvoked = true }

        service.startTimer(3_000)
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue("onTimerExpired must be called on expiry", callbackInvoked)
        assertSame(TimerState.Off, service.timerState.value)
    }

    /** Timer expiry falls back to stop() when no callback is set. */
    @Test
    fun timer_expiry_falls_back_to_stop() = runTest(testDispatcher) {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.onTimerExpired = null
        service.startTimer(3_000)
        advanceTimeBy(3_000)
        runCurrent()

        assertSame(TimerState.Off, service.timerState.value)
        assertFalse("Engine must be stopped on timer expiry fallback", fakeController.isPlaying)
    }

    /** cancelTimer() stops the countdown. */
    @Test
    fun cancel_timer_stops_countdown() = runTest(testDispatcher) {
        val service = serviceController.create().get()

        service.startTimer(10_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(TimerState.Armed(8_000), service.timerState.value)

        service.cancelTimer()
        assertSame(TimerState.Off, service.timerState.value)

        // Further time advancement must not re-arm.
        advanceTimeBy(10_000)
        runCurrent()
        assertSame(TimerState.Off, service.timerState.value)
    }

    /** stop() also cancels any running timer. */
    @Test
    fun stop_cancels_timer() = runTest(testDispatcher) {
        val service = serviceController.create().get()
        service.start()

        service.startTimer(60_000)
        runCurrent()
        assertEquals(TimerState.Armed(60_000), service.timerState.value)

        service.stop()
        assertSame(TimerState.Off, service.timerState.value)
    }

    // ── T36 — Notification stop action ───────────────────────────────

    /**
     * T36 — Notification has a stop action that triggers service stop.
     *
     * Verifies:
     * 1. The foreground notification has at least one action.
     * 2. The action is labeled "Stop".
     * 3. Sending ACTION_STOP intent stops the engine (already covered by
     *    action_stop_intent_delegates_to_stop, but T36 confirms the
     *    notification action is wired).
     */
    @Test
    fun t36_notification_has_stop_action() {
        val service = serviceController.create().get()
        service.start()

        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull("Notification must exist", notification)

        val actions = notification.actions
        assertNotNull("Notification must have actions", actions)
        assertTrue("Notification must have at least one action", actions.isNotEmpty())
        assertEquals("Stop", actions[0].title.toString())
    }

    // ── onTaskRemoved ────────────────────────────────────────────────

    /** Swiping from recents stops playback (D-29). */
    @Test
    fun on_task_removed_stops_playback() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.onTaskRemoved(null)

        assertFalse("Engine must be stopped after task removed", fakeController.isPlaying)
        assertEquals(1, fakeController.stopCalls.get())
    }

    // ── Audio focus ──────────────────────────────────────────────────

    /** Audio focus is requested on start (Robolectric auto-grants). */
    @Test
    fun start_requests_audio_focus() {
        val service = serviceController.create().get()

        // Robolectric's AudioManager auto-grants focus. If focus was not
        // requested, start() would return early (no engine). Verifying
        // the engine started proves focus was requested and granted.
        service.start()
        assertTrue("Engine must start (focus granted)", service.isPlaying)
    }

    /** Audio focus loss stops playback. */
    @Test
    fun audio_focus_loss_stops_playback() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        // Simulate audio focus loss via the internal listener.
        service.audioFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse("Engine must be stopped on focus loss", fakeController.isPlaying)
    }

    /** Transient audio focus loss also stops playback (sleep app behavior). */
    @Test
    fun audio_focus_transient_loss_stops_playback() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.audioFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse("Engine must be stopped on transient focus loss", fakeController.isPlaying)
    }

    /** Ducking focus loss is ignored — sleep noise should continue through chimes. */
    @Test
    fun audio_focus_duck_does_not_stop_playback() {
        val service = serviceController.create().get()
        service.start()
        assertTrue(service.isPlaying)

        service.audioFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue("Engine must keep playing on duck", fakeController.isPlaying)
        assertEquals(0, fakeController.stopCalls.get())
    }

    // ── onStopRequested callback routing ─────────────────────────────

    /** ACTION_STOP with onStopRequested set invokes callback, not direct stop. */
    @Test
    fun action_stop_routes_through_callback_when_set() {
        val service = serviceController.create().get()
        service.start()

        var callbackInvoked = false
        service.onStopRequested = { callbackInvoked = true }

        service.onStartCommand(Intent(PlaybackService.ACTION_STOP), 0, 1)

        assertTrue("onStopRequested must be called", callbackInvoked)
        // Direct stop NOT called — callback handles it.
        assertTrue("Engine still playing (callback doesn't call stop)", fakeController.isPlaying)
    }

    /** Focus loss with onStopRequested set invokes callback, not direct stop. */
    @Test
    fun audio_focus_loss_routes_through_callback_when_set() {
        val service = serviceController.create().get()
        service.start()

        var callbackInvoked = false
        service.onStopRequested = { callbackInvoked = true }

        service.audioFocusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue("onStopRequested must be called on focus loss", callbackInvoked)
        assertTrue("Engine still playing (callback doesn't call stop)", fakeController.isPlaying)
    }

    /** onTaskRemoved always calls stop() directly (no fade needed on dismissal). */
    @Test
    fun on_task_removed_always_stops_directly() {
        val service = serviceController.create().get()
        service.start()

        var callbackInvoked = false
        service.onStopRequested = { callbackInvoked = true }

        service.onTaskRemoved(null)

        assertFalse("onStopRequested must NOT be called on task removed", callbackInvoked)
        assertFalse("Engine must be stopped directly", fakeController.isPlaying)
        assertEquals(1, fakeController.stopCalls.get())
    }

    /** After nulling onStopRequested, ACTION_STOP falls back to direct stop. */
    @Test
    fun action_stop_falls_back_to_direct_stop_when_callback_null() {
        val service = serviceController.create().get()
        service.start()

        service.onStopRequested = { /* set */ }
        service.onStopRequested = null // dispose

        service.onStartCommand(Intent(PlaybackService.ACTION_STOP), 0, 1)

        assertFalse("Engine must be stopped via fallback", fakeController.isPlaying)
    }

    // ── pendingSnapGain ─────────────────────────────────────────────

    /** snapGain before start is buffered and applied when engine is created. */
    @Test
    fun snap_gain_before_start_is_buffered() {
        val service = serviceController.create().get()

        service.snapGain(0f)
        assertEquals("No engine yet, no snapGain call", 0, fakeController.snapGainCalls.size)

        service.start()

        assertEquals("Buffered snapGain applied on start", 1, fakeController.snapGainCalls.size)
        assertEquals(0f, fakeController.snapGainCalls[0], 0.001f)
    }

    /** snapGain after start delegates immediately (no buffering). */
    @Test
    fun snap_gain_after_start_delegates_immediately() {
        val service = serviceController.create().get()
        service.start()
        fakeController.snapGainCalls.clear() // clear any from start

        service.snapGain(0.5f)

        assertEquals(1, fakeController.snapGainCalls.size)
        assertEquals(0.5f, fakeController.snapGainCalls[0], 0.001f)
    }

    /** Multiple snapGain calls before start: only the last value is applied. */
    @Test
    fun multiple_snap_gain_before_start_keeps_last() {
        val service = serviceController.create().get()

        service.snapGain(0.3f)
        service.snapGain(0.7f)

        service.start()

        assertEquals("Only one snapGain call on start", 1, fakeController.snapGainCalls.size)
        assertEquals(0.7f, fakeController.snapGainCalls[0], 0.001f)
    }

    /** stop() clears pendingSnapGain so next start is clean. */
    @Test
    fun stop_clears_pending_snap_gain() {
        val service = serviceController.create().get()

        service.snapGain(0f)  // buffered
        service.start()       // applied
        service.stop()        // clears pending

        // Create a fresh fake for the second start cycle.
        val freshFake = FakeController()
        PlaybackService.controllerFactory = { freshFake }

        service.start()

        assertEquals("No stale snapGain on second start", 0, freshFake.snapGainCalls.size)
    }
}
