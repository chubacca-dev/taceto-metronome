package de.takt.audio

import de.takt.core.PcmRenderer
import de.takt.core.Settings
import de.takt.core.SoundBank
import kotlin.math.min

/** Gives the output route time to settle before musical frame zero, without changing its gain. */
internal class PrimedAudioStream(sampleRate: Int, initial: Settings, bank: SoundBank, warmupMillis: Int = 40) {
    init { require(warmupMillis in 0..1000) }
    val warmupFrames = sampleRate * warmupMillis.toLong() / 1000
    val renderer = PcmRenderer(sampleRate, initial, soundBank = bank)
    private var outputFrames = 0L

    fun render(destination: FloatArray, frameCount: Int) {
        require(frameCount in 0..destination.size)
        val silent = min(frameCount.toLong(), (warmupFrames - outputFrames).coerceAtLeast(0)).toInt()
        destination.fill(0f, 0, silent)
        if (silent < frameCount) renderer.render(destination, silent, frameCount - silent)
        outputFrames += frameCount
    }

    fun musicalFrame(deviceFrame: Long): Long = (deviceFrame - warmupFrames).coerceAtLeast(0)

    fun positionAt(deviceFrame: Long) = if (deviceFrame < warmupFrames || renderer.renderedFrames == 0L) null
        else renderer.positionAt(musicalFrame(deviceFrame).coerceAtMost(renderer.renderedFrames))
}

/** Display-only route calibration; musical output and its clock never change. */
internal fun visualPlaybackFrame(heardFrame: Long, sampleRate: Int, delayMs: Int): Long =
    (heardFrame - sampleRate * delayMs.coerceIn(0, 1000).toLong() / 1000).coerceAtLeast(0)
