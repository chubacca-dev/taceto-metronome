package de.takt.core

import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    @Test fun eachBarUsesItsOwnSubdivisionAndSwing() {
        val settings = Settings(bars = listOf(
            PatternBar(Meter(1, 4), subdivision = Subdivision.EIGHTH, swingEnabled = false),
            PatternBar(Meter(1, 4), subdivision = Subdivision.SIXTEENTH, swingEnabled = true, swing = .75)))
        val r = PcmRenderer(8000, settings)
        r.render(FloatArray(8001))
        assertEquals(listOf(0L, 2000L, 4000L, 5500L, 6000L, 7500L, 8000L),
            r.eventsBetween(0, 8001).map { it.frame })
        assertEquals(Subdivision.EIGHTH, r.positionAt(0).settings.subdivision)
        assertEquals(Subdivision.SIXTEENTH, r.positionAt(4000).settings.subdivision)
        assertEquals(.75, r.positionAt(4000).settings.swing, 0.0)
    }
    @Test fun beatOmissionsProtectOneAndItsSubdivisionsAcrossMixedMeters() {
        val settings = Settings(subdivision = Subdivision.SIXTEENTH,
            bars = listOf(PatternBar(), PatternBar(Meter(7, 8))),
            training = Training(omission = Omission.BEATS, probability = 100))
        val renderer = PcmRenderer(8000, settings)
        renderer.render(FloatArray(60000))
        val events = renderer.eventsBetween(0, 60000)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.beatIndex == 0 })
        assertTrue(events.filter { it.kind == EventKind.ACCENT }.size >= 4)
        assertTrue(events.any { it.kind == EventKind.SUBDIVISION })
    }

    @Test fun silentBarsAreBoundedAndDoNotMoveClock() {
        val renderer = PcmRenderer(8000, Settings(training = Training(omission = Omission.BARS,
            probability = 100, maxSilentBars = 2)))
        renderer.render(FloatArray(112001))
        assertEquals(listOf(2L, 5L), renderer.eventsBetween(0, 112001).filter { it.beatIndex == 0 }.map { it.barIndex })
        assertEquals(7L, renderer.positionAt(112000).barIndex)
        assertTrue(renderer.positionAt(0).trainingMuted)
        assertFalse(renderer.positionAt(32000).trainingMuted)
    }

    @Test fun rampWaitsForBarClampsTargetAndResetsOnRestart() {
        val s = Settings(training = Training(ramp = true, startBpm = 120, step = 7,
            intervalSeconds = 1, targetBpm = 130))
        val r = PcmRenderer(8000, s)
        r.render(FloatArray(40001))
        assertEquals(120, r.positionAt(15999).settings.bpm)
        assertEquals(127, r.positionAt(16000).settings.bpm)
        assertEquals(130, r.positionAt(32000).settings.bpm)
        r.reset(); r.render(FloatArray(1))
        assertEquals(120, r.positionAt(0).settings.bpm)
    }

    @Test fun manualTempoCancelsRampOnlyForThisRun() {
        val s = Settings(training = Training(ramp = true, intervalSeconds = 1))
        val r = PcmRenderer(8000, s)
        r.render(FloatArray(4001)); r.submitSettings(s.copy(bpm = 100))
        r.render(FloatArray(40000))
        assertEquals(100, r.positionAt(44000).settings.bpm)
        r.reset(); r.render(FloatArray(1))
        assertEquals(120, r.positionAt(0).settings.bpm)
    }

    @Test fun randomOmissionsAreReproducibleAndBlockIndependent() {
        val s = Settings(training = Training(omission = Omission.BEATS, probability = 50))
        val a = PcmRenderer(8000, s, randomSeed = 42)
        val b = PcmRenderer(8000, s, randomSeed = 42)
        val whole = FloatArray(64000); a.render(whole)
        val chunks = FloatArray(64000)
        repeat(100) { b.render(chunks, it * 640, 640) }
        assertArrayEquals(whole, chunks, 0f)
        assertEquals(a.eventsBetween(0, 64000), b.eventsBetween(0, 64000))
    }
}
