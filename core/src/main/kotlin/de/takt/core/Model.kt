package de.takt.core

enum class BeatState { ACCENT, NORMAL, MUTED }
enum class Sound(val tonal: Boolean) {
    STANDARDCLICK(true), CUT(true), WOODBLOCK(true), HIHAT(false), SNARE(false), COWBELL(true);
    companion object {
        fun fromStored(value: String): Sound = when (value) {
            "CLICK" -> CUT
            "IMPULSE" -> STANDARDCLICK
            else -> valueOf(value)
        }
    }
}
enum class Subdivision { NONE, EIGHTH, SIXTEENTH }
enum class Omission { OFF, BEATS, BARS }
data class Training(val omission: Omission = Omission.OFF, val probability: Int = 30,
    val maxSilentBars: Int = 2, val visuals: Boolean = true, val ramp: Boolean = false,
    val startBpm: Int = 120, val step: Int = 5, val intervalSeconds: Int = 120, val targetBpm: Int = 180) {
    init {
        require(probability in 0..100 && maxSilentBars in 1..100)
        require(startBpm in 1..999 && targetBpm in startBpm..999 && step in 1..999 && intervalSeconds in 1..86400)
    }
}

data class Meter(val numerator: Int = 4, val denominator: Int = 4) {
    init {
        require(numerator in 1..1024) { "Numerator must be in 1..1024" }
        require(denominator in 1..1024 && denominator and (denominator - 1) == 0) {
            "Denominator must be a power of two in 1..1024"
        }
    }
    val beatQuarters: Double get() = 4.0 / denominator
    val quarters: Double get() = numerator * beatQuarters
}

data class Settings(
    val bpm: Int = 120,
    val reference: Double = 1.0,
    val meter: Meter = Meter(),
    val beats: List<BeatState> = emptyList(),
    val subdivision: Subdivision = Subdivision.NONE,
    val swingEnabled: Boolean = false,
    val swing: Double = 2.0 / 3.0,
    val sound: Sound = Sound.STANDARDCLICK,
    val pitches: Map<Sound, Int> = emptyMap(),
    val bars: List<PatternBar> = emptyList(),
    val training: Training = Training(),
) {
    init {
        require(bpm in 1..999) { "BPM must be in 1..999" }
        require(reference.isFinite() && reference in (1.0 / 256.0)..16.0) { "Invalid reference" }
        require(beats.isEmpty() || beats.size == meter.numerator) { "Beat count must match meter" }
        require(swing.isFinite() && swing in 0.5..0.75) { "Swing must be in 0.5..0.75" }
        require(pitches.all { (sound, pitch) -> pitch in -12..12 && (sound.tonal || pitch == 0) })
    }
    val effectiveBeats: List<BeatState>
        get() = if (beats.isEmpty()) List(meter.numerator) {
            if (it == 0) BeatState.ACCENT else BeatState.NORMAL
        } else beats.toList()
    fun pitchFor(sound: Sound = this.sound): Int = pitches[sound] ?: 0
    internal fun snapshot() = copy(beats = beats.toList(), pitches = pitches.toMap(), bars = bars.map { it.snapshot() })
}

enum class EventKind { ACCENT, NORMAL, SUBDIVISION }
/** Null rhythm overrides preserve legacy patterns that used session-wide rhythm settings. */
data class PatternBar(val meter: Meter = Meter(), val beats: List<BeatState> = emptyList(),
    val subdivision: Subdivision? = null, val swingEnabled: Boolean? = null, val swing: Double? = null) {
    init {
        require(beats.isEmpty() || beats.size == meter.numerator)
        require(swing == null || (swing.isFinite() && swing in 0.5..0.75))
    }
    fun applyTo(settings: Settings): Settings = settings.copy(meter = meter, beats = beats,
        subdivision = subdivision ?: settings.subdivision, swingEnabled = swingEnabled ?: settings.swingEnabled,
        swing = swing ?: settings.swing)
    internal fun snapshot() = copy(beats = beats.toList())
}
data class ClickEvent(val frame: Long, val quarterPosition: Double, val barIndex: Long,
    val beatIndex: Int, val kind: EventKind, val sound: Sound)
data class MusicalPosition(val frame: Long, val quarterPosition: Double, val barIndex: Long,
    val beatIndex: Int, val beatPhase: Double, val pulseIndex: Long, val pulsePhase: Double,
    val settings: Settings, val patternBarIndex: Int = 0, val loopIndex: Long = 0,
    val trainingMuted: Boolean = false)
