package net.uncorp.reedr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }
enum class ReaderFont { SERIF, SANS }

data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Body text size in CSS px. */
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.6f,
    val downloadImages: Boolean = true,
    /** Move an article to the Archive when you reach its end. */
    val archiveAtEnd: Boolean = false,
    /** Record opened articles in History. */
    val keepHistory: Boolean = true,
    /** Text size of the article lists, set by pinching them (1 = normal). */
    val listScale: Float = 1f,
)

/** Small key-value settings; a StateFlow so Compose and the save worker see changes immediately. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val reader: StateFlow<ReaderPrefs> = state.asStateFlow()

    fun update(transform: (ReaderPrefs) -> ReaderPrefs) {
        val next = transform(state.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putString("font", next.font.name)
            .putFloat("textSize", next.fontSize)
            .putFloat("lineHeight", next.lineHeight)
            .putBoolean("downloadImages", next.downloadImages)
            .putBoolean("archiveAtEnd", next.archiveAtEnd)
            .putBoolean("keepHistory", next.keepHistory)
            .putFloat("listScale", next.listScale)
            .apply()
        state.value = next
    }

    private fun load(): ReaderPrefs {
        val d = ReaderPrefs()
        return ReaderPrefs(
            theme = enumOr(prefs.getString("theme", null), d.theme),
            font = enumOr(prefs.getString("font", null), d.font),
            fontSize = prefs.getFloat("textSize", d.fontSize),
            lineHeight = prefs.getFloat("lineHeight", d.lineHeight),
            downloadImages = prefs.getBoolean("downloadImages", d.downloadImages),
            archiveAtEnd = prefs.getBoolean("archiveAtEnd", d.archiveAtEnd),
            keepHistory = prefs.getBoolean("keepHistory", d.keepHistory),
            listScale = prefs.getFloat("listScale", d.listScale),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
