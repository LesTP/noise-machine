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
import com.noisemachine.app.playback.TimerController
import com.noisemachine.app.playback.TimerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [AudioEngine] lifecycle and timer
 * countdown (D-28).
 *
 * The service implements [PlaybackController] directly — the ViewModel binds
 * via [LocalBinder] and delegates all playback control through this interface.
 *
 * It also implements [TimerController] so the countdown coroutine runs in
 * [serviceScope] and survives ViewModel/Activity destruction.
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
class PlaybackService : Service(), PlaybackController, TimerController {

    private var engine: PlaybackController? = null

    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(timerDispatcher + supervisorJob)
    private var timerJob: Job? = null

    // -- TimerController ---------------------------------------------------

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Off)
    override val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    override var onTimerExpired: (() -> Unit)? = null

    override fun startTimer(durationMs: Long) {
        timerJob?.cancel()
        _timerState.value = TimerState.Armed(durationMs)
        timerJob = serviceScope.launch {
            var remaining = durationMs
            while (remaining > 0) {
            delay(1000)
                remaining -= 1000
                _timerState.value = TimerState.Armed(maxOf(0, remaining))
            }
            _timerState.value = TimerState.Off
            (onTimerExpired ?: { stop() }).invoke()
        }
    }

    override fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerState.value = TimerState.Off
    }

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
        cancelTimer()
        supervisorJob.cancel()
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
        cancelTimer()
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

        /** Timer dispatcher — swappable for tests (inject StandardTestDispatcher). */
        internal var timerDispatcher: CoroutineDispatcher = Dispatchers.Main
    }
}
