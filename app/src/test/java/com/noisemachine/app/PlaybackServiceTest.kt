package com.noisemachine.app

import android.content.Intent
import com.noisemachine.app.audio.PlaybackController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Unit tests for [PlaybackService] covering Phase-4 test spec T32/T33.
 *
 * Uses Robolectric to shadow the Android Service lifecycle. The real
 * [AudioEngine] is replaced with a [FakeController] via the service's
 * [PlaybackService.controllerFactory] seam.
 *
 * Step 4.2: Tests exercise the [PlaybackController] interface that the
 * service now implements (binder path), rather than intent-based start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceTest {

    private lateinit var fakeController: FakeController
    private lateinit var serviceController: ServiceController<PlaybackService>

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
        override fun snapGain(gain: Float) {}
    }

    @Before
    fun setUp() {
        fakeController = FakeController()
        PlaybackService.controllerFactory = { fakeController }
        serviceController = Robolectric.buildService(PlaybackService::class.java)
    }

    @After
    fun tearDown() {
        PlaybackService.controllerFactory = { throw IllegalStateException("not wired") }
    }

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
}
