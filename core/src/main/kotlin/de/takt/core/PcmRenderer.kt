package de.takt.core

import kotlin.math.*

/** Single continuous musical clock; integer frames are rounded only at the output boundary. */
class PcmRenderer(val sampleRate: Int, initialSettings: Settings = Settings(),
    pattern: List<PatternBar>? = null, private val soundBank: SoundBank = SoundBank.prepare(sampleRate),
    randomSeed: Long = System.nanoTime()) {
    init { require(sampleRate in 8000..384000 && soundBank.sampleRate == sampleRate) }
    private var active = initialSettings.snapshot()
    private var requested = active
    private var pattern = snapshotPattern(pattern ?: initialSettings.bars.takeIf { it.isNotEmpty() })
    private var requestedPattern = this.pattern
    private var patternBarIndex = -1
    private var loopIndex = -1L
    var earliestSupportedFrame: Long = 0
        private set
    var renderedFrames: Long = 0
        private set
    private var q = 0.0
    private var exactFrame = 0.0
    private var nextPulse = 0.0
    private var pulseIndex = -1L
    private var pulseStart = 0.0
    private var barStart = 0.0
    private var nextBar = 0.0
    private var barIndex = -1L
    private var grid = emptyList<Pair<Double, EventKind>>()
    private var gridIndex = 0
    private val events = mutableListOf<ClickEvent>()
    private data class Segment(val frame: Double, val q: Double, val barStart: Double,
        val bar: Long, val pulseStart: Double, val pulse: Long, val settings: Settings,
        val patternBar: Int, val loop: Long, val muted: Boolean)
    private val segments = mutableListOf<Segment>()
    private data class Voice(val frame: Long, val samples: FloatArray)
    private val voices = mutableListOf<Voice>()
    private val random = kotlin.random.Random(randomSeed)
    private var silentBars = 0
    private var barMuted = false
    private var pulseMuted = false
    private var rampCancelled = false
    private var nextRampFrame = initialSettings.training.intervalSeconds.toDouble() * sampleRate
    private var rampBpm = if (initialSettings.training.ramp) initialSettings.training.startBpm else initialSettings.bpm

    @Synchronized fun submitSettings(settings: Settings) {
        if (settings.bpm != requested.bpm) rampCancelled = true
        if (settings.training != requested.training) {
            rampCancelled = false
            rampBpm = active.bpm
            nextRampFrame = exactFrame + settings.training.intervalSeconds.toDouble() * sampleRate
        }
        requested = settings.snapshot()
        requestedPattern = snapshotPattern(settings.bars.takeIf { it.isNotEmpty() })
    }
    @Synchronized fun cancelTempoRamp() { rampCancelled = true }

    /** Atomically replaces the structure at the next loop; null returns to a single settings bar. */
    @Synchronized fun submitPattern(pattern: List<PatternBar>?) {
        requestedPattern = snapshotPattern(pattern)
    }

    /** Discards history strictly before frame, retaining the position segment that spans frame. */
    @Synchronized fun pruneBefore(frame: Long) {
        require(frame in earliestSupportedFrame..renderedFrames)
        if (segments.isEmpty()) render(FloatArray(0))
        var predecessor = 0
        while (predecessor + 1 < segments.size &&
            segments[predecessor + 1].frame.roundToLong() <= frame) predecessor++
        if (predecessor > 0) segments.subList(0, predecessor).clear()
        val firstEvent = events.indexOfFirst { it.frame >= frame }.let { if (it < 0) events.size else it }
        if (firstEvent > 0) events.subList(0, firstEvent).clear()
        earliestSupportedFrame = frame
    }

    @Synchronized fun reset(settings: Settings = requested) {
        active = settings.snapshot(); requested = active
        renderedFrames = 0; q = 0.0; exactFrame = 0.0; nextPulse = 0.0
        pulseIndex = -1; pulseStart = 0.0; barStart = 0.0; nextBar = 0.0; barIndex = -1
        grid = emptyList(); gridIndex = 0
        pattern = requestedPattern; patternBarIndex = -1; loopIndex = -1
        earliestSupportedFrame = 0
        events.clear(); segments.clear(); voices.clear()
        silentBars = 0; barMuted = false; pulseMuted = false; rampCancelled = false
        rampBpm = if (active.training.ramp) active.training.startBpm else active.bpm
        nextRampFrame = active.training.intervalSeconds.toDouble() * sampleRate
    }

    @Synchronized fun render(destination: FloatArray, offset: Int = 0,
        frameCount: Int = destination.size - offset) {
        require(offset >= 0 && frameCount >= 0 && offset <= destination.size - frameCount)
        val end = Math.addExact(renderedFrames, frameCount.toLong())
        // Process boundaries at end too, so positionAt(exclusive write head) is defined.
        while (true) {
            val eventQ = grid.getOrNull(gridIndex)?.first?.plus(barStart) ?: Double.POSITIVE_INFINITY
            val target = min(nextPulse, min(nextBar, eventQ))
            val targetFrame = exactFrame + (target - q) * framesPerQuarter(active)
            if (targetFrame.roundToLong() > end) break
            exactFrame = targetFrame; q = target
            var boundary = false
            if (abs(q - nextPulse) < EPS) {
                active = active.copy(bpm = if (requested.training.ramp && !rampCancelled) rampBpm else requested.bpm, reference = requested.reference)
                pulseStart = q; pulseIndex++; nextPulse = q + active.reference
                pulseMuted = requested.training.omission == Omission.BEATS && random.nextInt(100) < requested.training.probability
                boundary = true
            }
            if (abs(q - nextBar) < EPS) {
                val loopBoundary = patternBarIndex < 0 || patternBarIndex + 1 >= (pattern?.size ?: 1)
                if (loopBoundary) {
                    pattern = requestedPattern; patternBarIndex = 0; loopIndex++
                } else patternBarIndex++
                val definition = pattern?.get(patternBarIndex)
                active = (definition?.applyTo(requested) ?: requested).copy(
                    bpm = active.bpm, reference = active.reference).snapshot()
                barStart = q; barIndex++; nextBar = q + active.meter.quarters
                val training = requested.training
                if (training.ramp && !rampCancelled && exactFrame + EPS >= nextRampFrame) {
                    rampBpm = (rampBpm + training.step).coerceAtMost(training.targetBpm)
                    active = active.copy(bpm = rampBpm)
                    nextRampFrame = exactFrame + training.intervalSeconds.toDouble() * sampleRate
                }
                barMuted = training.omission == Omission.BARS && silentBars < training.maxSilentBars && random.nextInt(100) < training.probability
                silentBars = if (barMuted) silentBars + 1 else 0
                grid = buildGrid(active); gridIndex = 0; boundary = true
            }
            if (boundary) segments.add(Segment(exactFrame, q, barStart, barIndex,
                pulseStart, pulseIndex, active, patternBarIndex, loopIndex,
                barMuted || (pulseMuted && q - barStart >= active.meter.beatQuarters - EPS)))
            val point = grid.getOrNull(gridIndex)
            if (point != null && abs(barStart + point.first - q) < EPS) {
                val frame = exactFrame.roundToLong()
                val beat = floor((q - barStart) / active.meter.beatQuarters + EPS).toInt()
                val event = ClickEvent(frame, q, barIndex, beat, point.second, active.sound)
                val protectedOne = q - barStart < active.meter.beatQuarters - EPS
                if (!barMuted && !(pulseMuted && !protectedOne)) {
                    events.add(event)
                    voices.add(Voice(frame, soundBank.samples(active.sound, active.pitchFor(), point.second)))
                }
                gridIndex++
            }
        }
        destination.fill(0f, offset, offset + frameCount)
        for (voice in voices) {
            val start = max(renderedFrames, voice.frame)
            val stop = min(end, voice.frame + voice.samples.size)
            for (frame in start until stop) {
                val index = offset + (frame - renderedFrames).toInt()
                destination[index] += voice.samples[(frame - voice.frame).toInt()]
            }
        }
        for (i in offset until offset + frameCount) destination[i] = destination[i].coerceIn(-1f, 1f)
        voices.removeAll { it.frame + it.samples.size <= end }
        renderedFrames = end
    }

    @Synchronized fun eventsBetween(fromFrame: Long, untilFrame: Long): List<ClickEvent> {
        require(fromFrame >= earliestSupportedFrame && untilFrame >= fromFrame && untilFrame <= renderedFrames)
        return events.filter { it.frame >= fromFrame && it.frame < untilFrame }
    }

    @Synchronized fun positionAt(frame: Long): MusicalPosition {
        require(frame in earliestSupportedFrame..renderedFrames)
        if (segments.isEmpty()) render(FloatArray(0))
        // Binary search by quantized boundary: position and audible event agree within one sample.
        var low = 0; var high = segments.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (segments[mid].frame.roundToLong() <= frame) low = mid else high = mid - 1
        }
        val s = segments[low]
        val position = s.q + max(0.0, frame - s.frame) / framesPerQuarter(s.settings)
        val beat = (position - s.barStart) / s.settings.meter.beatQuarters
        val pulse = (position - s.pulseStart) / s.settings.reference
        return MusicalPosition(frame, position, s.bar, floor(beat + EPS).toInt()
            .coerceIn(0, s.settings.meter.numerator - 1), beat.mod(1.0),
            s.pulse, pulse.coerceIn(0.0, 1.0), s.settings, s.patternBar, s.loop, s.muted)
    }

    private fun framesPerQuarter(s: Settings) = sampleRate * 60.0 / (s.bpm * s.reference)

    companion object {
        private const val EPS = 1e-9
        private fun snapshotPattern(pattern: List<PatternBar>?): List<PatternBar>? {
            require(pattern == null || pattern.isNotEmpty()) { "Pattern must contain at least one bar" }
            return pattern?.map { it.snapshot() }
        }
        /** Actual audible event grid, also suitable for settings previews. */
        fun buildGrid(settings: Settings): List<Pair<Double, EventKind>> {
            val meter = settings.meter
            val beats = settings.effectiveBeats
            val result = sortedMapOf<Double, EventKind>()
            val unit = when (settings.subdivision) {
                Subdivision.NONE -> null; Subdivision.EIGHTH -> 0.5; Subdivision.SIXTEENTH -> 0.25
            }
            if (unit != null) {
                val swing = if (settings.swingEnabled) settings.swing else 0.5
                var i = 0
                while (i * unit < meter.quarters - EPS) {
                    // A notated main beat replaces its original grid point; it is never swung.
                    val originalBeat = i * unit / meter.beatQuarters
                    if (abs(originalBeat - originalBeat.roundToInt()) < EPS) { i++; continue }
                    val pairStart = (i / 2) * 2 * unit
                    val fullPair = pairStart + 2 * unit <= meter.quarters + EPS
                    val point = if (i % 2 == 1 && fullPair) pairStart + 2 * unit * swing else i * unit
                    val beat = floor(point / meter.beatQuarters + EPS).toInt()
                    if (point < meter.quarters - EPS && beats[beat] != BeatState.MUTED)
                        result[point] = EventKind.SUBDIVISION
                    i++
                }
            }
            beats.forEachIndexed { i, state ->
                if (state != BeatState.MUTED) result[i * meter.beatQuarters] =
                    if (state == BeatState.ACCENT) EventKind.ACCENT else EventKind.NORMAL
            }
            return result.entries.map { it.key to it.value }
        }
    }
}
