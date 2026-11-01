package de.takt.audio

import de.takt.core.*
import org.junit.Assert.*
import org.junit.Test

class PrimedAudioStreamTest {
    @Test fun startupSilencePreservesEverySampleAndFirstAccentGainForAllSounds() {
        val rate = 48000
        val bank = SoundBank.prepare(rate)
        for (sound in Sound.entries) {
            val settings = Settings(sound = sound)
            val stream = PrimedAudioStream(rate, settings, bank)
            val expected = FloatArray(108000)
            PcmRenderer(rate, settings, soundBank = bank).render(expected)
            val output = FloatArray(stream.warmupFrames.toInt() + expected.size)
            // Non-dividing block size exercises the silence/music boundary and partial last block.
            var offset = 0
            while (offset < output.size) {
                val block = FloatArray(minOf(257, output.size - offset))
                stream.render(block, block.size)
                block.copyInto(output, offset)
                offset += block.size
            }
            val start = stream.warmupFrames.toInt()
            assertTrue(output.take(start).all { it == 0f })
            assertArrayEquals(expected, output.copyOfRange(start, output.size), 0f)
            // First accent and the next bar's accent are identical, not "fixed" by boosting gain.
            assertArrayEquals(output.copyOfRange(start, start + 12000),
                output.copyOfRange(start + 96000, start + 108000), 0f)
        }
    }

    @Test fun displayClockExcludesWarmupAndNoPositionIsPublishedDuringSilence() {
        val stream = PrimedAudioStream(48000, Settings(), SoundBank.prepare(48000))
        stream.render(FloatArray(10000), 10000)
        assertNull(stream.positionAt(stream.warmupFrames - 1))
        val start = requireNotNull(stream.positionAt(stream.warmupFrames))
        assertEquals(0L, start.frame)
        assertEquals(0, start.beatIndex)
        assertEquals(0.0, start.quarterPosition, 0.0)
        assertEquals(0L, stream.musicalFrame(100))
        assertEquals(100L, stream.musicalFrame(stream.warmupFrames + 100))
    }
}
