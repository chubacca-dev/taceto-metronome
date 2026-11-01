package de.takt.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class PcmRendererTest {
    @Test fun mixedMetersLoopAtFiveAndQuarterSecondsWithoutPulseReset() {
        val r = PcmRenderer(48000, pattern = listOf(PatternBar(Meter(4, 4)),
            PatternBar(Meter(7, 8)), PatternBar(Meter(3, 4))))
        r.render(FloatArray(504001))
        val starts = r.eventsBetween(0, 504001).filter { it.beatIndex == 0 }
        assertEquals(listOf(0L, 96000L, 180000L, 252000L, 348000L, 432000L, 504000L),
            starts.map { it.frame })
        assertEquals(2, r.positionAt(180000).patternBarIndex)
        assertEquals(0.5, r.positionAt(180000).pulsePhase, 1e-9)
        val loop = r.positionAt(252000)
        assertEquals(1L, loop.loopIndex)
        assertEquals(0, loop.patternBarIndex)
        assertEquals(10L, loop.pulseIndex)
        assertEquals(0.5, loop.pulsePhase, 1e-9)
        assertEquals(21L, r.positionAt(504000).pulseIndex)
    }

    @Test fun structureReplacedOnlyAtFullLoop() {
        val r = PcmRenderer(48000, pattern = listOf(PatternBar(), PatternBar(Meter(3, 4))))
        r.render(FloatArray(100))
        r.submitPattern(listOf(PatternBar(Meter(7, 8))))
        r.render(FloatArray(168000))
        assertEquals(Meter(3, 4), r.positionAt(96000).settings.meter)
        assertEquals(Meter(7, 8), r.positionAt(168000).settings.meter)
        assertEquals(1L, r.positionAt(168000).loopIndex)
    }

    @Test fun pruningKeepsPredecessorAndDoesNotAffectFuturePcm() {
        val a = PcmRenderer(48000); val b = PcmRenderer(48000)
        a.render(FloatArray(25000)); b.render(FloatArray(25000))
        val expected = a.positionAt(24500)
        a.pruneBefore(24500)
        assertEquals(24500L, a.earliestSupportedFrame)
        assertEquals(expected, a.positionAt(24500))
        assertEquals(b.positionAt(24900), a.positionAt(24900))
        try { a.positionAt(24499); fail("Pruned frame accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(a.eventsBetween(24500, 25000).isEmpty())
        val x = FloatArray(100000); val y = FloatArray(100000)
        a.render(x); b.render(y); assertArrayEquals(x, y, 0f)
        a.pruneBefore(a.renderedFrames)
        assertEquals(b.positionAt(b.renderedFrames), a.positionAt(a.renderedFrames))
        a.reset(); assertEquals(0L, a.earliestSupportedFrame)
        assertEquals(0L, a.positionAt(0).frame)
    }

    private fun events(s: Settings, frames: Int = 96000): List<ClickEvent> {
        val r = PcmRenderer(48000, s)
        r.render(FloatArray(frames))
        return r.eventsBetween(0, frames.toLong())
    }

    @Test fun swingExamplesAndMainBeatPriority() {
        val s = Settings(subdivision = Subdivision.EIGHTH, swingEnabled = true, swing = 0.6)
        assertEquals(listOf(0L, 14400L, 24000L, 38400L), events(s).take(4).map { it.frame })
        val sixteenths = s.copy(subdivision = Subdivision.SIXTEENTH)
        assertEquals(listOf(0L, 7200L, 12000L, 19200L, 24000L),
            events(sixteenths).take(5).map { it.frame })
        val seven = s.copy(meter = Meter(7, 8))
        assertEquals((0..6).map { it * 12000L }, events(seven, 84000).map { it.frame })
    }

    @Test fun mutedMainSuppressesEntireInterval() {
        val s = Settings(beats = listOf(BeatState.ACCENT, BeatState.MUTED,
            BeatState.NORMAL, BeatState.NORMAL), subdivision = Subdivision.SIXTEENTH)
        val es = events(s)
        assertTrue(es.none { it.frame in 24000L until 48000L })
        assertEquals(EventKind.ACCENT, es.first().kind)
        assertEquals(es.size, es.map { it.frame }.distinct().size)
    }

    @Test fun incompletePairAndDisabledSwingStayStraight() {
        val s = Settings(meter = Meter(3, 16), subdivision = Subdivision.EIGHTH,
            swingEnabled = true, swing = 0.75)
        // Final raster point at 1/2 is already a notated main beat; no phantom swung click.
        assertEquals(listOf(0.0, 0.25, 0.5), PcmRenderer.buildGrid(s).map { it.first })
        val disabled = Settings(subdivision = Subdivision.EIGHTH, swing = 0.75)
        assertEquals(listOf(0L, 12000L, 24000L), events(disabled).take(3).map { it.frame })
    }

    @Test fun referenceChangePreservesPositionAndNewestRequestWins() {
        val r = PcmRenderer(48000)
        r.render(FloatArray(100))
        r.submitSettings(Settings(reference = 0.5, sound = Sound.WOODBLOCK))
        r.submitSettings(Settings(reference = 1.5, sound = Sound.SNARE,
            subdivision = Subdivision.SIXTEENTH))
        r.render(FloatArray(80000))
        val boundary = r.positionAt(24000)
        assertEquals(1.0, boundary.quarterPosition, 1e-9)
        assertEquals(1.5, boundary.settings.reference, 0.0)
        assertEquals(Sound.STANDARDCLICK, boundary.settings.sound)
        // q=4 at 72000 frames after the reference change; bar settings arrive there.
        assertEquals(Sound.SNARE, r.positionAt(72000).settings.sound)
        assertEquals(Subdivision.SIXTEENTH, r.positionAt(72000).settings.subdivision)
        assertEquals(0.0, r.positionAt(72000).pulsePhase, 1e-9)
    }

    @Test fun dottedSixEightAndMarkers() {
        val r = PcmRenderer(48000, Settings(bpm = 80, reference = 1.5, meter = Meter(6, 8),
            subdivision = Subdivision.EIGHTH, swingEnabled = true))
        r.render(FloatArray(72001))
        assertEquals((0..5).map { it * 12000L }, r.eventsBetween(0, 72000).map { it.frame })
        assertEquals(1L, r.positionAt(36000).pulseIndex)
        assertEquals(1L, r.positionAt(72000).barIndex)
        assertEquals(2L, r.positionAt(72000).pulseIndex)
    }

    @Test fun oddLoopDoesNotResetReferencePhase() {
        val r = PcmRenderer(48000, Settings(meter = Meter(7, 8)))
        r.render(FloatArray(84001))
        val p = r.positionAt(84000)
        assertEquals(1L, p.barIndex)
        assertEquals(3L, p.pulseIndex)
        assertEquals(0.5, p.pulsePhase, 1e-9)
    }

    @Test fun liveChangesHaveIndependentBoundariesAndHistoricalPosition() {
        val r = PcmRenderer(48000)
        r.render(FloatArray(1000))
        r.submitSettings(Settings(bpm = 60, meter = Meter(3, 4), sound = Sound.COWBELL))
        r.render(FloatArray(170000))
        assertEquals(120, r.positionAt(1000).settings.bpm)
        assertEquals(60, r.positionAt(24000).settings.bpm)
        assertEquals(Meter(), r.positionAt(24000).settings.meter)
        assertEquals(Meter(3, 4), r.positionAt(168000).settings.meter)
        assertEquals(Sound.COWBELL, r.eventsBetween(168000, 168001).single().sound)
        assertEquals(4.0, r.positionAt(168000).quarterPosition, 1e-9)
    }

    @Test fun longRunHasNoAccumulatingSampleRoundingAndBlocksAgree() {
        val s = Settings(bpm = 137)
        val r = PcmRenderer(8000, s)
        val block = FloatArray(4096)
        // More than one simulated hour, no per-frame musical clock accumulation.
        repeat(7100) { r.render(block) }
        val es = r.eventsBetween(0, r.renderedFrames)
        es.forEachIndexed { i, event ->
            assertTrue(kotlin.math.abs(event.frame - (i * 8000.0 * 60 / 137).roundToLong()) <= 1)
        }
        val a = PcmRenderer(48000, s); val b = PcmRenderer(48000, s)
        val whole = FloatArray(96000); val split = FloatArray(96000)
        a.render(whole)
        for (i in 0 until 96) b.render(split, i * 1000, 1000)
        assertArrayEquals(whole, split, 0f)
    }

    @Test fun validationAndReset() {
        listOf<() -> Any>({ Settings(bpm = 0) }, { Settings(reference = Double.NaN) },
            { Settings(swing = 0.49) }, { Meter(0, 4) }, { Meter(4, 3) },
            { Settings(beats = listOf(BeatState.NORMAL)) }).forEach { invalid ->
            try { invalid(); fail("Expected validation") } catch (_: IllegalArgumentException) { }
        }
        val r = PcmRenderer(48000); r.render(FloatArray(500)); r.reset()
        assertEquals(0L, r.renderedFrames)
        val pcm = FloatArray(1); r.render(pcm)
        assertEquals(0f, pcm[0], 0f) // approved samples have a click-free zero attack
    }
}
