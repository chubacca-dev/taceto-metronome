package de.takt.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingPreviewTest {
    @Test fun previewRespectsStepClampingAndBarBoundaries() {
        val s = Settings(training = Training(ramp = true, startBpm = 120, step = 7,
            intervalSeconds = 1, targetBpm = 130))
        assertEquals(2.0 + 240.0 / 127, trainingDurationSeconds(s), 1e-8)
    }
    @Test fun mixedMetersContinueAtTheNextBarAndReferenceAffectsDuration() {
        val s = Settings(bars = listOf(PatternBar(), PatternBar(Meter(3, 4))),
            training = Training(ramp = true, intervalSeconds = 1, targetBpm = 130))
        assertEquals(2.0 + 180.0 / 125, trainingDurationSeconds(s), 1e-8)
        assertEquals(1.0 + 210.0 / 125, trainingDurationSeconds(s.copy(reference = 2.0)), 1e-8)
    }
    @Test fun longIntervalsSkipFullLoopsAndExactTargetsNeedNoExtraStep() {
        val s = Settings(training = Training(ramp = true, startBpm = 120, targetBpm = 125,
            intervalSeconds = 86400))
        assertEquals(86400.0, trainingDurationSeconds(s), 1e-8)
        assertEquals(0.0, trainingDurationSeconds(s.copy(training = s.training.copy(targetBpm = 120))), 0.0)
    }
    @Test fun previewAgreesWithActualRendererAtTheFinalTempo() {
        val s = Settings(bars = listOf(PatternBar(Meter(7, 8)), PatternBar(Meter(3, 4))),
            training = Training(ramp = true, startBpm = 120, targetBpm = 132, intervalSeconds = 1))
        val seconds = trainingDurationSeconds(s)
        val frame = kotlin.math.ceil(seconds * 8000).toInt()
        val r = PcmRenderer(8000, s)
        r.render(FloatArray(frame + 1))
        assertEquals(132, r.positionAt(frame.toLong()).settings.bpm)
        assertEquals(130, r.positionAt((frame - 2).toLong()).settings.bpm)
    }
}
