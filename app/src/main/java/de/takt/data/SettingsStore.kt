package de.takt.data

import android.content.Context
import de.takt.core.BeatState
import de.takt.core.Meter
import de.takt.core.Settings
import de.takt.core.Sound
import de.takt.core.Subdivision
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, app-private storage. Corrupt/incompatible values never prevent app startup. */
class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("takt.settings", Context.MODE_PRIVATE)

    fun load(): Settings = runCatching {
        val raw = preferences.getString("settings", null) ?: return Settings()
        val json = JSONObject(raw)
        require(json.getInt("version") in 1..2)
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
        )
    }.getOrDefault(Settings())

    fun save(settings: Settings) {
        val json = JSONObject().put("version", 2)
            .put("bpm", settings.bpm).put("reference", settings.reference)
            .put("numerator", settings.meter.numerator).put("denominator", settings.meter.denominator)
            .put("beats", JSONArray(settings.beats.map { it.name }))
            .put("subdivision", settings.subdivision.name).put("swingEnabled", settings.swingEnabled)
            .put("swing", settings.swing).put("sound", settings.sound.name)
            .put("pitches", JSONObject().also { pitches ->
                Sound.entries.filter { it.tonal }.forEach { pitches.put(it.name, settings.pitchFor(it)) }
            })
        preferences.edit().putString("settings", json.toString()).apply()
    }

    fun loadViewPreferences(): ViewPreferences = ViewPreferences(
        pendulum = preferences.getBoolean("pendulum", false),
        showTrainingVisuals = preferences.getBoolean("trainingVisuals", true),
        accent = AccentColor.entries.firstOrNull { it.name == preferences.getString("accentColor", null) } ?: AccentColor.GREEN,
    )

    fun saveViewPreferences(value: ViewPreferences) {
        preferences.edit().putBoolean("pendulum", value.pendulum)
            .putBoolean("trainingVisuals", value.showTrainingVisuals)
            .putString("accentColor", value.accent.name).apply()
    }
}

enum class AccentColor { GREEN, ORANGE, CYAN }
data class ViewPreferences(val pendulum: Boolean = false, val showTrainingVisuals: Boolean = true,
    val accent: AccentColor = AccentColor.GREEN)
