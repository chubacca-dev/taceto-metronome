package de.takt.audio

import de.takt.core.PcmRenderer
import de.takt.core.Settings
import de.takt.core.Training
import org.junit.Assert.assertEquals
import org.junit.Test

class TempoFeedbackTest {
    private fun position(bpm: Int) = PcmRenderer(8000, Settings(bpm = bpm)).let {
        it.render(FloatArray(1)); it.positionAt(0)
    }

    @Test fun liveControlsDoNotWaitForOrRevertToTheHeardTempo() {
        var state = PlaybackState(running = true, settings = Settings(bpm = 80), position = position(60))
        assertEquals(80, state.displayedBpm)
        repeat(10) { state = state.copy(settings = state.settings.copy(bpm = state.displayedBpm + 1)) }
        assertEquals(90, state.displayedBpm)
        state = state.copy(position = position(70))
        assertEquals(90, state.displayedBpm)
    }

    @Test fun automaticRampStillShowsActualProgressAndItsStartingTempo() {
        val state = PlaybackState(running = true, settings = Settings(bpm = 100,
            training = Training(ramp = true, startBpm = 120)), position = position(135))
        assertEquals(135, state.displayedBpm)
        assertEquals(120, state.copy(position = null).displayedBpm)
        assertEquals(100, state.copy(running = false, position = null).displayedBpm)
    }

    @Test fun manualInterventionInRampAcknowledgesInputDespiteOldAudioReports() {
        val state = PlaybackState(running = true, settings = Settings(bpm = 125,
            training = Training(ramp = true)), position = position(150), manualTempo = true)
        assertEquals(125, state.displayedBpm)
        assertEquals(125, state.copy(position = position(155)).displayedBpm)
    }
}
