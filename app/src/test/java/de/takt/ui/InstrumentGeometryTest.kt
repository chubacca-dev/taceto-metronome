package de.takt.ui

import de.takt.core.BeatState
import de.takt.core.Meter
import de.takt.core.MusicalPosition
import de.takt.core.PcmRenderer
import de.takt.core.Settings
import de.takt.core.Subdivision
import org.junit.Assert.assertEquals
import org.junit.Test

class InstrumentGeometryTest {
    private val compound = Settings(meter = Meter(6, 8), reference = 1.5, subdivision = Subdivision.EIGHTH)

    @Test fun dottedQuarterFirstPulseShowsAbsoluteEighths() {
        assertPhases(compound, 0.0, listOf(1.0 / 3, 2.0 / 3))
    }

    @Test fun dottedQuarterSecondPulseShowsAbsoluteEighths() {
        assertPhases(compound, 1.5, listOf(1.0 / 3, 2.0 / 3))
    }

    @Test fun swingDoesNotMoveNotatedEighthMainBeats() {
        assertPhases(compound.copy(swingEnabled = true), 1.5, listOf(1.0 / 3, 2.0 / 3))
    }

    @Test fun pulseWindowIncludesEventsAcrossUnalignedBarBoundary() {
        assertPhases(Settings(meter = Meter(7, 8)), 3.0, listOf(.5))
    }

    @Test fun swingMarkerUsesExactTwoToOneEventPosition() {
        assertPhases(Settings(subdivision = Subdivision.EIGHTH, swingEnabled = true), .2, listOf(2.0 / 3))
    }

    @Test fun mutedMainBeatAlsoRemovesItsSubdivisionMarkers() {
        assertPhases(Settings(subdivision = Subdivision.EIGHTH, swingEnabled = true,
            beats = listOf(BeatState.MUTED, BeatState.NORMAL, BeatState.NORMAL, BeatState.NORMAL)), .2, emptyList())
    }

    @Test fun straightSixteenthsHaveThreeInteriorMarkers() {
        assertPhases(Settings(subdivision = Subdivision.SIXTEENTH), .1, listOf(.25, .5, .75))
    }

    private fun assertPhases(settings: Settings, quarter: Double, expected: List<Double>) {
        val bar = (quarter / settings.meter.quarters).toLong()
        val inBar = quarter - bar * settings.meter.quarters
        val beat = (inBar / settings.meter.beatQuarters).toInt()
        val pulse = (quarter / settings.reference).toLong()
        val position = MusicalPosition(0, quarter, bar, beat,
            inBar / settings.meter.beatQuarters - beat, pulse,
            quarter / settings.reference - pulse, settings)
        val actual = markerPhases(settings, position, PcmRenderer.buildGrid(settings))
        assertEquals("Marker count at quarter $quarter", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            assertEquals("Marker $index at quarter $quarter", want, got, 1e-8)
        }
    }
}
