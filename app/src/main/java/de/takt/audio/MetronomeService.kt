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
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        MetronomeController.initialize(this)
        audioManager = requireNotNull(getSystemService(AudioManager::class.java))
        updateChannel()
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
        if (MetronomeController.state.value.running)
            requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            refreshLanguage()
            if (!MetronomeController.state.value.running) stopSelf()
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
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val start = PendingIntent.getService(this, 2,
            Intent(this, MetronomeService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(localized.getString(R.string.app_name))
            .setContentText(localized.getString(R.string.metronome_running)).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), localized.getString(R.string.start), start).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), localized.getString(R.string.stop), stop).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun stopPlayback() {
        MetronomeController.stopEngine()
        releaseResources()
        stopSelf()
    }

    private fun releaseResources() {
        if (receiverRegistered) {
            unregisterReceiver(noisy)
            receiverRegistered = false
        }
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        MetronomeController.stopEngine()
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "de.takt.audio.START"
        const val ACTION_STOP = "de.takt.audio.STOP"
        const val ACTION_REFRESH = "de.takt.audio.REFRESH_LANGUAGE"
        private const val CHANNEL = "metronome.playback"
        private const val NOTIFICATION_ID = 41
    }
}
