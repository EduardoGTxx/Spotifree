package com.spotifree.app.ui.i18n

import com.spotifree.app.util.runCatchingCancellable

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI texts in Spanish, English, Portuguese and French (the previous app's languages). The language
 * is an in-app setting, like before, so texts live in assets/i18n/<lang>.json; missing keys fall
 * back to Spanish and then English.
 */
class Strings(val lang: String, private val table: Map<String, String>, private val fallbacks: List<Map<String, String>>) {
    operator fun get(key: String): String = table[key] ?: fallbacks.firstNotNullOfOrNull { it[key] } ?: key

    /** Replaces {0}, {1}… with [args]. */
    fun f(key: String, vararg args: Any?): String {
        var s = get(key)
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a?.toString().orEmpty()) }
        return s
    }

    /** "1 canción" / "12 canciones". */
    fun songs(n: Int) = if (n == 1) f("songCountOne", n) else f("songCountMany", n)

    companion object {
        val EMPTY = Strings("es", emptyMap(), emptyList())
        val LANGUAGES = listOf("es" to "Español", "en" to "English", "pt" to "Português", "fr" to "Français")
        private val cache = HashMap<String, Map<String, String>>()
        private val json = Json { ignoreUnknownKeys = true }

        private fun table(context: Context, lang: String): Map<String, String> = synchronized(cache) {
            cache.getOrPut(lang) {
                runCatchingCancellable {
                    val text = context.assets.open("i18n/$lang.json").use { it.readBytes().toString(Charsets.UTF_8) }
                    (json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                }.getOrDefault(emptyMap())
            }
        }

        fun load(context: Context, lang: String): Strings {
            val l = if (LANGUAGES.any { it.first == lang }) lang else "es"
            return Strings(l, table(context, l), listOf(table(context, "es"), table(context, "en")))
        }
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.EMPTY }

@Composable
@ReadOnlyComposable
fun t(key: String): String = LocalStrings.current[key]

@Composable
@ReadOnlyComposable
fun t(key: String, vararg args: Any?): String = LocalStrings.current.f(key, *args)
