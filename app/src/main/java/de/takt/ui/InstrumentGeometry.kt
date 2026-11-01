package de.takt.ui

import de.takt.core.EventKind
import de.takt.core.MusicalPosition
import de.takt.core.Settings
import kotlin.math.floor

/** Project actual grid events into the heard reference-pulse window, including bar crossings. */
internal fun markerPhases(settings: Settings, position: MusicalPosition?, grid: List<Pair<Double, EventKind>>): List<Double> {
    val q = position?.quarterPosition ?: 0.0
    val phase = position?.pulsePhase ?: 0.0
    val start = q - phase * settings.reference
    val end = start + settings.reference
    val currentBarStart = q - ((position?.beatIndex ?: 0) + (position?.beatPhase ?: 0.0)) * settings.meter.beatQuarters
    val first = floor((start - currentBarStart) / settings.meter.quarters).toLong()
    val last = floor((end - currentBarStart) / settings.meter.quarters).toLong()
    return (first..last).flatMap { bar ->
        val offset = currentBarStart + bar * settings.meter.quarters
        grid.mapNotNull { (time, _) ->
            val p = (offset + time - start) / settings.reference
            p.takeIf { it > .000001 && it < .999999 }
        }
    }.distinct()
}
