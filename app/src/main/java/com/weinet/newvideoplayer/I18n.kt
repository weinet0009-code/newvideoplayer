package com.weinet.newvideoplayer

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * App language. "Follow the phone" by default; a chosen language is kept by the system on
 * Android 13+ (LocaleManager, also shown in system settings) and in prefs on older versions.
 * Strings are read through [tr] so code outside Compose can use them too. Some labels are read
 * once per process (enum labels), so a language change restarts the app.
 */
object I18n {
    /** Language tag to the language's own name. */
    val LANGUAGES = listOf(
        "zh-TW" to "繁體中文", "zh-CN" to "简体中文", "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "th" to "ไทย", "vi" to "Tiếng Việt", "id" to "Bahasa Indonesia", "ms" to "Bahasa Melayu", "fil" to "Filipino",
        "hi" to "हिन्दी", "bn" to "বাংলা", "ur" to "اردو", "ar" to "العربية", "fa" to "فارسی", "he" to "עברית",
        "tr" to "Türkçe", "es" to "Español", "pt-BR" to "Português (Brasil)", "fr" to "Français",
        "de" to "Deutsch", "it" to "Italiano", "nl" to "Nederlands", "ru" to "Русский", "uk" to "Українська",
        "pl" to "Polski", "cs" to "Čeština", "ro" to "Română", "sv" to "Svenska", "el" to "Ελληνικά",
    )

    private lateinit var app: Context
    @Volatile private var res: Resources? = null
    /** Locale the strings of this process were loaded in. */
    var startLocale: Locale? = null
        private set

    fun init(context: Context) {
        app = context.applicationContext
        val l = locale(app)
        startLocale = l
        if (chosenTag(app) != null) Locale.setDefault(l)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("i18n", Context.MODE_PRIVATE)

    /** The chosen language tag, or null for "follow the phone". */
    fun chosenTag(c: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else prefs(c).getString("lang", null)

    fun locale(c: Context): Locale =
        chosenTag(c)?.let { Locale.forLanguageTag(it) } ?: Resources.getSystem().configuration.locales[0]

    /** A context whose resources and layout direction follow the app language. */
    fun wrap(base: Context): Context {
        val l = locale(base)
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(l)
        conf.setLayoutDirection(l)
        return base.createConfigurationContext(conf)
    }

    fun resources(): Resources = res ?: wrap(app).resources.also { res = it }

    /** Saves the language (null = follow the phone) and restarts the app so every label switches. */
    fun set(activity: Activity, tag: String?) {
        prefs(activity).edit().putString("lang", tag).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
        restart(activity)
    }

    /** Restarts the process when the language changed while it ran (e.g. from system settings). */
    fun restartIfChanged(activity: Activity): Boolean {
        val now = locale(activity)
        if (startLocale == null || now == startLocale) return false
        restart(activity); return true
    }

    private fun restart(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

/** A string in the app language; [args] fill %1$s, %2$s… */
fun tr(@StringRes id: Int, vararg args: Any?): String = I18n.resources().getString(id, *args)
