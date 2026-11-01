package de.takt.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatLightTest {
    @Test fun clearOnsetAndDarkGapAcrossTempoRange() {
        for (bpm in listOf(1, 60, 120, 240, 999)) {
            assertEquals(.85f, beatLight(0.0, bpm, false), .0001f)
            assertEquals(0f, beatLight(.66, bpm, false), .0001f)
            assertEquals(0f, beatLight(.99, bpm, false), .0001f)
            var previous = 1f
            for (step in 0..100) {
                val current = beatLight(step / 100.0, bpm, false)
                assertTrue(current in 0f.. .85f)
                assertTrue(current <= previous)
                previous = current
            }
        }
    }

    @Test fun realTimeEnvelopeAndReducedMotion() {
        // At these tempos the envelope lasts 240 ms, regardless of beat duration.
        for (bpm in listOf(60, 120)) {
            fun phase(seconds: Double) = seconds * bpm / 60
            assertEquals(.85f, beatLight(phase(.030), bpm, false), .0001f)
            assertTrue(beatLight(phase(.150), bpm, false) in .1f.. .8f)
            assertEquals(0f, beatLight(phase(.241), bpm, false), .0001f)
            assertEquals(.85f, beatLight(phase(.100), bpm, true), .0001f)
            assertEquals(0f, beatLight(phase(.121), bpm, true), .0001f)
        }
    }
}
