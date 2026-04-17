package com.noisemachine.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * Audio focus: requests `AUDIOFOCUS_GAIN` on start, releases on stop.
 * Focus loss (permanent or transient) stops playback — for a sleep noise
 * app, an interruption means the user was woken and will restart manually.
 *
 * Lifecycle:
 * - Activity calls `startService()` to put the service in "started" state,
 *   then `bindService()` to get a [PlaybackController] reference.
 * - [start] creates the engine, requests audio focus, calls `startForeground()`,
 *   and starts playback.
 * - [stop] stops the engine, abandons audio focus, removes the notification,
 *   and calls `stopSelf()`.
 * - [onTaskRemoved] stops playback when the user swipes from recents (D-29).
 * - [onDestroy] is a safety net to stop the engine if still running.
 *
 * The service is `START_NOT_STICKY` — it does not auto-restart after the
 * system kills it. The user re-launches explicitly.
 */
class PlaybackService : Service(), PlaybackController, TimerController {

    private var engine: PlaybackController? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(timerDispatcher + supervisorJob)
    private var timerJob: Job? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stop()
        }
    }

    /** Exposed for test access to simulate focus loss. */
    internal val audioFocusListener: AudioManager.OnAudioFocusChangeListener
        get() = focusListener

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
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelTimer()
        supervisorJob.cancel()
        abandonFocus()
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    // -- PlaybackController ------------------------------------------------

    override val isPlaying: Boolean
        get() = engine?.isPlaying == true

    override fun start() {
        if (engine?.isPlaying == true) return
        if (!requestFocus()) return
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
        abandonFocus()
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

    // -- Audio focus -------------------------------------------------------

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true // no AudioManager = test environment
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
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
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(
            null,
            "Stop",
            stopPendingIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Noise Machine")
            .setContentText("Playing")
            .setOngoing(true)
            .addAction(stopAction)
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
