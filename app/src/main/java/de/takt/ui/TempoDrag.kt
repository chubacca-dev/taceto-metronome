package de.takt.ui

/** Gesture-local accumulation must not depend on delayed playback/UI acknowledgements. */
internal class TempoDrag(initialBpm: Int) {
    private var bpm = initialBpm
    private var remainder = 0f
    fun move(deltaDp: Float): Int? {
        remainder -= deltaDp
        val steps = (remainder / 8f).toInt()
        if (steps == 0) return null
        remainder -= steps * 8f
        bpm = (bpm + steps).coerceIn(1, 999)
        return bpm
    }
}
