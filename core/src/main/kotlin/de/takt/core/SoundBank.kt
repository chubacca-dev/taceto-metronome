package de.takt.core

import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Immutable, fully prepared PCM bank. Loading/resampling must happen before the audio worker. */
class SoundBank private constructor(val sampleRate: Int) {
    private data class Key(val sound: Sound, val pitch: Int, val kind: EventKind)
    private val samples: Map<Key, FloatArray> = buildMap {
        val resource = requireNotNull(SoundBank::class.java.getResourceAsStream("sounds.pcm.gz"))
        DataInputStream(GZIPInputStream(resource).buffered()).use { input ->
            check(input.readInt() == 0x54414331)
            repeat(input.readInt()) {
                val name = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                val sound = Sound.valueOf(name)
                val pitch = input.readInt()
                val accent = input.readInt() == 1
                val sourceRate = input.readInt()
                val source = FloatArray(input.readInt()) { input.readShort() / 32768f }
                val pcm = resample(source, sourceRate, sampleRate)
                put(Key(sound, pitch, if (accent) EventKind.ACCENT else EventKind.NORMAL), pcm)
                if (!accent) put(Key(sound, pitch, EventKind.SUBDIVISION), FloatArray(pcm.size) { pcm[it] * .45f })
            }
        }
    }

    internal fun samples(sound: Sound, pitch: Int, kind: EventKind): FloatArray =
        samples.getValue(Key(sound, pitch, kind))

    companion object {
        private val cache = ConcurrentHashMap<Int, SoundBank>()
        fun prepare(sampleRate: Int): SoundBank {
            require(sampleRate in 8000..384000)
            return cache.computeIfAbsent(sampleRate) { SoundBank(it) }
        }

        private fun resample(source: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to) return source
            val ratio = from.toDouble() / to
            val cutoff = min(1.0, to.toDouble() / from) * .94
            // Windowed-sinc interpolation also low-passes when the device rate is lower.
            return FloatArray((source.size / ratio).roundToInt()) { i ->
                val position = i * ratio
                val center = floor(position).toInt()
                var value = 0.0
                var weight = 0.0
                for (j in center - 16..center + 16) {
                    val distance = position - j
                    if (abs(distance) > 16) continue
                    val x = PI * distance * cutoff
                    val sinc = if (abs(x) < 1e-10) 1.0 else sin(x) / x
                    val w = cutoff * sinc * (.5 + .5 * cos(PI * distance / 16))
                    weight += w
                    if (j in source.indices) value += source[j] * w
                }
                (value / weight).toFloat().coerceIn(-1f, 1f)
            }
        }
    }
}
