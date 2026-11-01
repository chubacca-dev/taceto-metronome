package de.takt.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.graphics.drawable.Icon
import android.util.Log
import de.takt.AppLanguage
import de.takt.R

/** Foreground lifetime owns focus, noisy receiver and a partial CPU wake lock. */
class MetronomeService : Service() {
    private lateinit var audioManager: AudioManager
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private lateinit var mediaSession: MediaSession
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        MetronomeController.initialize(this)
        audioManager = requireNotNull(getSystemService(AudioManager::class.java))
        instance = this
        mediaSession = MediaSession(this, "Taceto").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MetronomeController.start(this@MetronomeService) }
                override fun onPause() { MetronomeController.stop(this@MetronomeService) }
                override fun onStop() { MetronomeController.stop(this@MetronomeService) }
            })
            isActive = true
        }
        updateChannel()
        updateMediaState(false)
    }

    private fun updateChannel() {
        val localized = AppLanguage.localizedContext(this)
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    private fun refreshLanguage() {
        updateChannel()
        updateMediaState(MetronomeController.state.value.running)
        requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            refreshLanguage()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        if (intent == null || !MetronomeController.acceptsStart(intent)) {
            if (!MetronomeController.state.value.running) stopSelf()
            return START_NOT_STICKY
        }
        try {
            // Must precede audio-focus request on Android versions requiring an active FGS.
            startForeground(NOTIFICATION_ID, notification())
            if (focus == null) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stopPlayback()
                    }.build()
                focus = request
                if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    MetronomeController.fail(R.string.error_audio_focus)
                    releaseResources()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            if (!receiverRegistered) {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(noisy, filter)
                receiverRegistered = true
            }
            if (wakeLock == null) {
                wakeLock = requireNotNull(getSystemService(PowerManager::class.java))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:metronome")
                    .also { it.setReferenceCounted(false); it.acquire() }
            }
            MetronomeController.beginPlayback { stopPlayback() }
            updateMediaState(true)
            requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification())
        } catch (error: Exception) {
            Log.e("TacetoAudio", "Playback service failed", error)
            MetronomeController.fail(R.string.error_audio_playback)
            releaseResources()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val localized = AppLanguage.localizedContext(this)
        val running = MetronomeController.state.value.running
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val start = PendingIntent.getForegroundService(this, 2,
            Intent(this, MetronomeService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(localized.getString(R.string.app_name))
            .setContentText(localized.getString(if (running) R.string.metronome_running else R.string.metronome_stopped))
            .setOngoing(running).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this,
                if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                localized.getString(if (running) R.string.stop else R.string.start), if (running) stop else start).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun stopPlayback() {
        MetronomeController.stopEngine()
        releaseResources()
        updateMediaState(false)
        requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification())
    }

    private fun updateMediaState(running: Boolean) {
        val localized = AppLanguage.localizedContext(this)
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, localized.getString(R.string.app_name))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, localized.getString(
                if (running) R.string.metronome_running else R.string.metronome_stopped)).build())
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (running) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (running) 1f else 0f).build())
    }

    private fun releaseResources() {
        if (receiverRegistered) {
            unregisterReceiver(noisy)
            receiverRegistered = false
        }
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        MetronomeController.stopEngine()
        releaseResources()
        mediaSession.release()
        instance = null
        requireNotNull(getSystemService(NotificationManager::class.java)).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private var instance: MetronomeService? = null
        internal fun stopActiveSession(): Boolean {
            val service = instance ?: return false
            service.stopPlayback()
            return true
        }
        const val ACTION_START = "de.takt.audio.START"
        const val ACTION_STOP = "de.takt.audio.STOP"
        const val ACTION_REFRESH = "de.takt.audio.REFRESH_LANGUAGE"
        private const val CHANNEL = "metronome.playback"
        private const val NOTIFICATION_ID = 41
    }
}
