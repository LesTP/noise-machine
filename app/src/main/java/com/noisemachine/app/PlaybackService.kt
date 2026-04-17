package com.noisemachine.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.noisemachine.app.audio.AudioEngine
import com.noisemachine.app.audio.AudioTrackSink
import com.noisemachine.app.audio.PlaybackController

/**
 * Foreground service that owns the [AudioEngine] lifecycle.
 *
 * The service implements [PlaybackController] directly — the ViewModel binds
 * via [LocalBinder] and delegates all playback control through this interface.
 *
 * Lifecycle:
 * - Activity calls `startService()` to put the service in "started" state,
 *   then `bindService()` to get a [PlaybackController] reference.
 * - [start] creates the engine, calls `startForeground()`, and starts playback.
 * - [stop] stops the engine, removes the notification, and calls `stopSelf()`.
 * - [onDestroy] is a safety net to stop the engine if still running.
 *
 * The service is `START_NOT_STICKY` — it does not auto-restart after the
 * system kills it. The user re-launches explicitly.
 */
class PlaybackService : Service(), PlaybackController {

    private var engine: PlaybackController? = null

    // -- Binder ------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // -- Service lifecycle -------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    // -- PlaybackController ------------------------------------------------

    override val isPlaying: Boolean
        get() = engine?.isPlaying == true

    override fun start() {
        if (engine?.isPlaying == true) return
        val newEngine = controllerFactory()
        engine = newEngine
        startForeground(NOTIFICATION_ID, buildNotification())
        newEngine.start()
    }

    override fun stop() {
        val e = engine ?: return
        e.stop()
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun setColor(color: Float) {
        engine?.setColor(color)
    }

    override fun setGain(gain: Float) {
        engine?.setGain(gain)
    }

    override fun snapGain(gain: Float) {
        engine?.snapGain(gain)
    }

    // -- Notification -----------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification while noise is playing"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Noise Machine")
            .setContentText("Playing")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.noisemachine.app.ACTION_STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playback"

        /** Engine factory — swappable for tests. */
        internal var controllerFactory: () -> PlaybackController =
            { AudioEngine(sinkFactory = { AudioTrackSink() }) }
    }
}
