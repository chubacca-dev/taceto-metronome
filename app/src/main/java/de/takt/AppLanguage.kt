package de.takt

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Platform locale persistence on API 33+, local preference on API 26–32. */
object AppLanguage {
    fun selection(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else context.getSharedPreferences("takt.language", 0).getString("tag", "").orEmpty()

    fun localizedContext(context: Context): Context {
        val selected = selection(context)
        val system = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).systemLocales
        else Resources.getSystem().configuration.locales
        val preferred = if (selected.isEmpty()) system else LocaleList.forLanguageTags(selected)
        val language = (0 until preferred.size()).map { preferred[it].language }
            .firstOrNull { it == "de" || it == "en" } ?: "en"
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(language)))
        return context.createConfigurationContext(config)
    }

    fun select(context: Context, tag: String) {
        require(tag in listOf("", "de", "en"))
        if (selection(context) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences("takt.language", 0).edit().putString("tag", tag).apply()
            var owner = context
            while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
            (owner as? Activity)?.recreate()
        }
    }
}
