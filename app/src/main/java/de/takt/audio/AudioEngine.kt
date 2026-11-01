package de.takt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.Build
import android.util.Log
import de.takt.core.MusicalPosition
import de.takt.core.Settings
import de.takt.core.SoundBank
import kotlin.math.max
import kotlin.math.min

/** One owner thread for rendering, writing, timestamps and AudioTrack release. */
internal class AudioEngine(
    initial: Settings,
    private val preview: Boolean = false,
    private val onPosition: (MusicalPosition) -> Unit,
    private val onFinished: (Throwable?) -> Unit,
) {
    @Volatile private var requested = initial.copy(beats = initial.beats.toList(), pitches = initial.pitches.toMap())
    @Volatile private var stopped = false
    private lateinit var bank: SoundBank
    private val thread = Thread({ runAudio() }, "Taceto-audio")
    private val preparation = Thread({
        try {
            val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                .takeIf { it in 8000..192000 } ?: 48000
            bank = SoundBank.prepare(rate)
            if (!stopped) thread.start()
        } catch (error: Exception) { if (!stopped) onFinished(error) }
    }, "Taceto-sound-loader")

    fun start() = preparation.start()
    fun update(settings: Settings) { requested = settings.copy(beats = settings.beats.toList(), pitches = settings.pitches.toMap()) }
    fun stop() { stopped = true; preparation.interrupt(); thread.interrupt() }

    private fun runAudio() {
        var track: AudioTrack? = null
        var failure: Throwable? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val rate = bank.sampleRate
            val minimumBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT)
            check(minimumBytes > 0) { "PCM-Float-Ausgabe wird vom Gerät nicht unterstützt ($minimumBytes)." }
            val blockFrames = max(64, rate / 200) // approximately 5 ms
            // Device capacity can exceed the desired software queue. Its start threshold
            // must never exceed that queue, or the mixer waits forever for more frames.
            val capacityBytes = max(minimumBytes, rate / 25 * Float.SIZE_BYTES)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack konnte nicht initialisiert werden." }
            val desiredQueue = max(blockFrames * 2, rate / 25)
            val startThreshold = if (Build.VERSION.SDK_INT >= 31) {
                track.setStartThresholdInFrames(min(desiredQueue, track.bufferSizeInFrames))
            } else {
                // API 26–30 cannot lower the start threshold independently. Respect the
                // actual native buffer size (including device-enforced minimums).
                track.setBufferSizeInFrames(desiredQueue)
                track.bufferSizeInFrames
            }
            check(startThreshold > 0) { "Ungültige AudioTrack-Startschwelle ($startThreshold)." }
            val queueLimit = max(desiredQueue, startThreshold).toLong()
            Log.i("TacetoAudio", "start rate=$rate capacity=${track.bufferCapacityInFrames} " +
                "buffer=${track.bufferSizeInFrames} threshold=$startThreshold queue=$queueLimit")
            val stream = PrimedAudioStream(rate, requested, bank)
            val renderer = stream.renderer
            var applied = requested
            val block = FloatArray(blockFrames)
            val timestamp = AudioTimestamp()
            val head = PlaybackHead()
            var written = 0L
            var offset = 0
            var remaining = 0
            var heard = 0L
            var lastReport = 0L
            var lastPrune = 0L
            var lastDiagnostic = 0L
            var lastProgress = System.nanoTime()
            var playing = false
            // Short previews must also supply enough frames to cross the start threshold.
            val previewEnd = max(stream.warmupFrames + (rate * 0.65).toLong(), startThreshold.toLong())
            while (!stopped) {
                val now = System.nanoTime()
                val rawHead = head.extend(track.playbackHeadPosition)
                if (now - lastDiagnostic >= 30_000_000_000L) {
                    Log.d("TacetoAudio", "progress written=$written head=$rawHead " +
                        "retainedFrom=${renderer.earliestSupportedFrame} underruns=${track.underrunCount}")
                    lastDiagnostic = now
                }
                var observed = rawHead
                if (track.getTimestamp(timestamp)) {
                    val age = now - timestamp.nanoTime
                    if (age in 0L..100_000_000L && timestamp.framePosition in 0..written) {
                        observed = max(rawHead, timestamp.framePosition + age * rate / 1_000_000_000L)
                    }
                }
                heard = max(heard, min(written, observed))
                if (now - lastReport >= 16_000_000L && written > 0) {
                    stream.positionAt(heard)?.let(onPosition)
                    lastReport = now
                }
                if (renderer.renderedFrames > 0 && now - lastPrune >= 1_000_000_000L) {
                    // Keep two seconds behind the heard frame, plus the renderer's
                    // retained predecessor segment. Never prune relative to the write head.
                    renderer.pruneBefore(max(renderer.earliestSupportedFrame, stream.musicalFrame(heard) - rate * 2L))
                    lastPrune = now
                }
                if (preview && heard >= previewEnd) break
                if (requested != applied) {
                    applied = requested
                    renderer.submitSettings(applied)
                }
                val available = (queueLimit - (written - rawHead).coerceAtLeast(0)).toInt()
                if (available > 0 && (!preview || written < previewEnd)) {
                    if (remaining == 0) {
                        val frames = if (preview) min(blockFrames.toLong(), previewEnd - written).toInt() else blockFrames
                        stream.render(block, frames)
                        offset = 0; remaining = frames
                    }
                    val count = track.write(block, offset, min(remaining, available), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "AudioTrack-Schreibfehler ($count)." }
                    if (count > 0) {
                        offset += count; remaining -= count; written += count
                        lastProgress = now
                        // Start only with enough data to cross the native threshold.
                        // Leading silence lets route/volume startup ramps settle before the accent.
                        if (!playing && written >= startThreshold) {
                            if (stopped) break
                            track.play()
                            playing = true
                        }
                    } else Thread.sleep(2)
                } else Thread.sleep(2)
                check(now - lastProgress < 3_000_000_000L) {
                    "Audioausgabe reagiert nicht mehr (geschrieben=$written, Kopf=$rawHead, " +
                        "Startschwelle=$startThreshold, Puffer=$queueLimit)."
                }
            }
        } catch (interrupted: InterruptedException) {
            if (!stopped) failure = interrupted
        } catch (error: Exception) {
            Log.e("TacetoAudio", "Audio worker failed", error)
            failure = error
        } finally {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            onFinished(failure)
        }
    }
}

/** Unsigned 32-bit playback head wraps after roughly 25 hours at 48 kHz. */
internal class PlaybackHead {
    private var previous = 0L
    private var wraps = 0L
    fun extend(value: Int): Long {
        val unsigned = value.toLong() and 0xffff_ffffL
        if (unsigned < previous && previous - unsigned > 0x8000_0000L) wraps += 1L shl 32
        previous = unsigned
        return wraps + unsigned
    }
}
