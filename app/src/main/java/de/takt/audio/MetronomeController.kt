package de.takt.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.takt.R
import de.takt.core.BeatState
import de.takt.core.Meter
import de.takt.core.MusicalPosition
import de.takt.core.Settings
import de.takt.core.Sound
import de.takt.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PlaybackState(
    val running: Boolean = false,
    val settings: Settings = Settings(),
    val position: MusicalPosition? = null,
    val pending: Boolean = false,
    val error: Int? = null,
)

/** Public UI entry point. All mutations are serialized on Android's main thread. */
object MetronomeController {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var store: SettingsStore? = null
    private var engine: AudioEngine? = null
    private var generation = 0L
    private var startRequested = false
    private var commandId = 0L

    private fun serialized(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    fun initialize(context: Context) = serialized {
        if (store == null) {
            store = SettingsStore(context)
            mutableState.value = mutableState.value.copy(settings = store!!.load())
        }
    }

    fun update(settings: Settings) {
        val snapshot = settings.copy(beats = settings.beats.toList(), pitches = settings.pitches.toMap())
        serialized {
            store?.save(snapshot)
            val current = mutableState.value
            mutableState.value = current.copy(settings = snapshot,
                pending = current.running && current.position?.settings != snapshot, error = null)
            if (current.running) engine?.update(snapshot)
        }
    }

    fun start(context: Context) = serialized {
        initialize(context)
        if (!mutableState.value.running && !startRequested) {
            startRequested = true
            val id = ++commandId
            try {
                context.applicationContext.startForegroundService(Intent(context, MetronomeService::class.java)
                    .setAction(MetronomeService.ACTION_START).putExtra("commandId", id))
            } catch (error: Exception) {
                startRequested = false
                Log.e("TacetoAudio", "Unable to start foreground playback", error)
                fail(R.string.error_background_start)
            }
        }
    }

    fun stop(context: Context) = serialized {
        startRequested = false
        commandId++
        stopEngine()
        context.applicationContext.stopService(Intent(context, MetronomeService::class.java))
    }

    fun preview(context: Context, sound: Sound) = serialized {
        initialize(context)
        if (!mutableState.value.running && !startRequested) {
            stopEngine()
            val previewSettings = Settings(bpm = 180, meter = Meter(2, 4),
                beats = listOf(BeatState.ACCENT, BeatState.NORMAL), sound = sound,
                pitches = mutableState.value.settings.pitches.toMap())
            launchEngine(previewSettings, true)
        }
    }

    internal fun beginPlayback(onFinished: () -> Unit) {
        startRequested = false
        if (mutableState.value.running) return
        stopEngine()
        mutableState.value = mutableState.value.copy(running = true, position = null, pending = false, error = null)
        launchEngine(mutableState.value.settings, false, onFinished)
    }

    internal fun acceptsStart(intent: Intent): Boolean =
        !intent.hasExtra("commandId") ||
            (intent.getLongExtra("commandId", -1) == commandId && startRequested)

    private fun launchEngine(settings: Settings, preview: Boolean, onFinished: () -> Unit = {}) {
        val token = ++generation
        // At most one UI-position delivery is queued, even while the main thread is busy.
        val latestPosition = AtomicReference<MusicalPosition?>(null)
        val deliveryQueued = AtomicBoolean(false)
        engine = AudioEngine(settings, preview, { position ->
            if (!preview) {
                latestPosition.set(position)
                if (deliveryQueued.compareAndSet(false, true)) main.post {
                    deliveryQueued.set(false)
                    val latest = latestPosition.getAndSet(null)
                    if (generation == token && latest != null) mutableState.value = mutableState.value.copy(
                        position = latest, pending = latest.settings != mutableState.value.settings)
                }
            }
        }, { error ->
            main.post {
                if (generation == token) {
                    if (error != null) Log.e("TacetoAudio", "Audio engine failed", error)
                    engine = null
                    mutableState.value = mutableState.value.copy(running = false, position = null,
                        pending = false, error = error?.let { R.string.error_audio_playback })
                    onFinished()
                }
            }
        }).also { it.start() }
    }

    internal fun stopEngine() {
        generation++
        engine?.stop(); engine = null
        mutableState.value = mutableState.value.copy(running = false, position = null, pending = false)
    }

    internal fun fail(message: Int) {
        startRequested = false
        stopEngine()
        mutableState.value = mutableState.value.copy(error = message)
    }
}
