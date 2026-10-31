package com.projectnyanko.companion.data.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language, independent of the phone's.
 *
 * A Vietnamese player on a Vietnamese phone gets Vietnamese by default, but the language has to be
 * changeable from inside the app — otherwise getting English back means changing the whole system
 * language. The choice is stored per app and applied by wrapping the base context, which works from
 * API 24 without pulling in AppCompat just for per-app locales.
 */
object AppLocale {
    const val SYSTEM = ""

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** `""` follows the phone, otherwise a BCP-47 tag such as `en` or `vi`. */
    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM).orEmpty()

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Wrap a base context in the chosen locale; a blank choice leaves the phone's own locale alone. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }
}
