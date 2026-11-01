package de.takt.core

import kotlin.math.floor

/** Ideal playback time to the final tempo, respecting bar boundaries and mixed-meter loops. */
fun trainingDurationSeconds(settings: Settings): Double {
    val t = settings.training
    if (!t.ramp || t.startBpm >= t.targetBpm) return 0.0
    val bars = settings.bars.map { it.meter.quarters }.ifEmpty { listOf(settings.meter.quarters) }
    val loopQuarters = bars.sum()
    var index = 0
    var bpm = t.startBpm
    var seconds = 0.0
    while (bpm < t.targetBpm) {
        val requiredQuarters = t.intervalSeconds * bpm * settings.reference / 60.0
        val cycles = floor(requiredQuarters / loopQuarters)
        var quarters = cycles * loopQuarters
        while (quarters + 1e-8 < requiredQuarters) {
            quarters += bars[index]
            index = (index + 1) % bars.size
        }
        seconds += quarters * 60.0 / (bpm * settings.reference)
        bpm = (bpm + t.step).coerceAtMost(t.targetBpm)
    }
    return seconds
}
