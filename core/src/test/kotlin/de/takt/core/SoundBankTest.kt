package de.takt.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SoundBankTest {
    @Test fun allApprovedSoundsHaveDistinctVariantsAndFixedPitchDurations() {
        val bank = SoundBank.prepare(48000)
        assertEquals(6, Sound.entries.size)
        Sound.entries.forEach { sound ->
            val original = bank.samples(sound, 0, EventKind.NORMAL)
            val accent = bank.samples(sound, 0, EventKind.ACCENT)
            assertFalse(original.contentEquals(accent))
            for (pitch in if (sound.tonal) -12..12 else 0..0) {
                for (kind in EventKind.entries) {
                    val pcm = bank.samples(sound, pitch, kind)
                    assertEquals(original.size, pcm.size)
                    assertTrue(pcm.all { it.isFinite() && abs(it) < 1f })
                    assertTrue(pcm.any { abs(it) > .01 })
                }
            }
            val subdivision = bank.samples(sound, 0, EventKind.SUBDIVISION)
            original.indices.forEach { assertEquals(original[it] * .45f, subdivision[it], 0f) }
        }
    }

    @Test fun tonalPitchDoublesFrequencyWithoutSpeedingUpEnvelope() {
        val bank = SoundBank.prepare(48000)
        fun crossings(pitch: Int, kind: EventKind) = bank.samples(Sound.STANDARDCLICK, pitch, kind)
            .take(960).zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
        for (kind in listOf(EventKind.NORMAL, EventKind.ACCENT)) {
            assertEquals(crossings(0, kind) * 2.0, crossings(12, kind).toDouble(), 2.0)
            assertEquals(crossings(0, kind) / 2.0, crossings(-12, kind).toDouble(), 2.0)
        }
    }

    @Test fun changingPitchDoesNotChangeEventFramesAndAppliesAtBarBoundary() {
        val renderer = PcmRenderer(48000)
        renderer.render(FloatArray(1000))
        renderer.submitSettings(Settings(pitches = mapOf(Sound.STANDARDCLICK to 12)))
        renderer.render(FloatArray(96000))
        assertEquals(0, renderer.positionAt(24000).settings.pitchFor())
        assertEquals(12, renderer.positionAt(96000).settings.pitchFor())
        assertEquals(listOf(0L, 24000L, 48000L, 72000L, 96000L),
            renderer.eventsBetween(0, 97000).map { it.frame })
    }

    @Test fun legacySoundNamesMigrateAndNonTonalPitchIsRejected() {
        assertEquals(Sound.CUT, Sound.fromStored("CLICK"))
        assertEquals(Sound.STANDARDCLICK, Sound.fromStored("IMPULSE"))
        assertEquals(Sound.SNARE, Sound.fromStored("SNARE"))
        try {
            Settings(pitches = mapOf(Sound.HIHAT to 1))
            fail("Non-tonal sound must not receive pitch")
        } catch (_: IllegalArgumentException) { }
    }
}
