package de.takt.data

import android.content.Context
import de.takt.core.BeatState
import de.takt.core.Meter
import de.takt.core.Settings
import de.takt.core.Sound
import de.takt.core.Subdivision
import de.takt.core.PatternBar
import de.takt.core.Training
import de.takt.core.Omission
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, app-private storage. Corrupt/incompatible values never prevent app startup. */
class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("takt.settings", Context.MODE_PRIVATE)

    fun load(key: String = "settings"): Settings = runCatching {
        val raw = preferences.getString(key, null) ?: return Settings()
        val json = JSONObject(raw)
        require(json.getInt("version") in 1..4)
        val beats = json.getJSONArray("beats")
        Settings(
            bpm = json.getInt("bpm"), reference = json.getDouble("reference"),
            meter = Meter(json.getInt("numerator"), json.getInt("denominator")),
            beats = List(beats.length()) { BeatState.valueOf(beats.getString(it)) },
            subdivision = Subdivision.valueOf(json.getString("subdivision")),
            swingEnabled = json.getBoolean("swingEnabled"), swing = json.getDouble("swing"),
            sound = Sound.fromStored(json.getString("sound")),
            pitches = Sound.entries.filter { it.tonal }.associateWith { sound ->
                json.optJSONObject("pitches")?.optInt(sound.name, 0)?.coerceIn(-12, 12) ?: 0
            },
            bars = json.optJSONArray("bars")?.let { array -> List(array.length()) {
                val bar = array.getJSONObject(it)
                val states = bar.getJSONArray("beats")
                PatternBar(Meter(bar.getInt("n"), bar.getInt("d")), List(states.length()) { i -> BeatState.valueOf(states.getString(i)) },
                    if (bar.has("subdivision")) Subdivision.valueOf(bar.getString("subdivision")) else null,
                    if (bar.has("swingEnabled")) bar.getBoolean("swingEnabled") else null,
                    if (bar.has("swing")) bar.getDouble("swing") else null)
            } } ?: emptyList(),
            training = json.optJSONObject("training")?.let { t -> Training(
                Omission.valueOf(t.getString("omission")), t.getInt("probability"), t.getInt("maxSilentBars"),
                t.getBoolean("visuals"), t.getBoolean("ramp"), t.getInt("startBpm"), t.getInt("step"),
                t.getInt("intervalSeconds"), t.getInt("targetBpm")) } ?: Training(),
        )
    }.getOrDefault(Settings())

    fun save(settings: Settings, key: String = "settings") {
        val json = JSONObject().put("version", 4)
            .put("bpm", settings.bpm).put("reference", settings.reference)
            .put("numerator", settings.meter.numerator).put("denominator", settings.meter.denominator)
            .put("beats", JSONArray(settings.beats.map { it.name }))
            .put("subdivision", settings.subdivision.name).put("swingEnabled", settings.swingEnabled)
            .put("swing", settings.swing).put("sound", settings.sound.name)
            .put("bars", JSONArray(settings.bars.map { bar -> JSONObject().put("n", bar.meter.numerator)
                .put("d", bar.meter.denominator).put("beats", JSONArray(bar.beats.map { it.name }))
                .also { j -> bar.subdivision?.let { j.put("subdivision", it.name) }
                    bar.swingEnabled?.let { j.put("swingEnabled", it) }; bar.swing?.let { j.put("swing", it) } } }))
            .put("training", settings.training.let { t -> JSONObject().put("omission", t.omission.name)
                .put("probability", t.probability).put("maxSilentBars", t.maxSilentBars).put("visuals", t.visuals)
                .put("ramp", t.ramp).put("startBpm", t.startBpm).put("step", t.step)
                .put("intervalSeconds", t.intervalSeconds).put("targetBpm", t.targetBpm) })
            .put("pitches", JSONObject().also { pitches ->
                Sound.entries.filter { it.tonal }.forEach { pitches.put(it.name, settings.pitchFor(it)) }
            })
        preferences.edit().putString(key, json.toString()).apply()
    }

    fun patternNames(): List<String> = preferences.all.keys.filter { it.startsWith("pattern:") }.map { it.removePrefix("pattern:") }.sorted()
    fun savePattern(name: String, settings: Settings) = save(settings, "pattern:$name")
    fun loadPattern(name: String): Settings = load("pattern:$name")
    fun deletePattern(name: String) { preferences.edit().remove("pattern:$name").apply() }
    fun currentPatternName(): String = preferences.getString("currentPatternName", "") ?: ""
    fun defaultTempo(fallback: Int): Int = preferences.getInt("defaultTempo", fallback)
    fun rememberPattern(name: String, tempo: Int) { preferences.edit().putString("currentPatternName", name).putInt("defaultTempo", tempo).apply() }

    fun loadViewPreferences(): ViewPreferences = ViewPreferences(
        pendulum = preferences.getBoolean("pendulum", false),
        showTrainingVisuals = preferences.getBoolean("trainingVisuals", true),
        accent = AccentColor.entries.firstOrNull { it.name == preferences.getString("accentColor", null) } ?: AccentColor.GREEN,
        visualDelayMs = preferences.getInt("visualDelayMs", 0).coerceIn(0, 1000),
    )

    fun saveViewPreferences(value: ViewPreferences) {
        preferences.edit().putBoolean("pendulum", value.pendulum)
            .putBoolean("trainingVisuals", value.showTrainingVisuals)
            .putString("accentColor", value.accent.name)
            .putInt("visualDelayMs", value.visualDelayMs.coerceIn(0, 1000))
            .remove("fastStart").apply()
    }
}

enum class AccentColor { GREEN, ORANGE, CYAN }
data class ViewPreferences(val pendulum: Boolean = false, val showTrainingVisuals: Boolean = true,
    val accent: AccentColor = AccentColor.GREEN, val visualDelayMs: Int = 0)
