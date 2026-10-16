package net.uncorp.reedr.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import net.uncorp.reedr.data.ArticleFilter
import net.uncorp.reedr.data.SaveOutcome
import net.uncorp.reedr.graph
import net.uncorp.reedr.ui.library.LibraryScreen
import net.uncorp.reedr.ui.library.SettingsScreen
import net.uncorp.reedr.ui.reader.ReaderScreen
import net.uncorp.reedr.AppTheme

/** The screens; encoded as strings so the back stack survives process death. */
sealed interface Screen {
    data object Library : Screen
    data class Reader(val id: Long) : Screen
    data object Settings : Screen

    fun encode(): String = when (this) {
        Library -> "library"; Settings -> "settings"
        is Reader -> "reader:$id"
    }

    companion object {
        fun decode(s: String): Screen = when {
            s.startsWith("reader:") -> Reader(s.split(":")[1].toLong())
            s == "settings" -> Settings
            else -> Library
        }
    }
}

class MainActivity : ComponentActivity() {
    /** Text shared to Reedr (via [SaveActivity]) that is still to be saved and opened. */
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) shared.value = intent.getStringExtra(EXTRA_SHARED)
        setContent { AppTheme(this) { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SHARED)?.let { shared.value = it }
    }

    @Composable
    private fun App() {
        val stack: SnapshotStateList<Screen> = rememberSaveable(
            saver = listSaver(save = { list -> list.map { it.encode() } }, restore = { saved -> mutableStateListOf(*saved.map(Screen::decode).toTypedArray()) }),
        ) { mutableStateListOf(Screen.Library) }
        val filterState = rememberSaveable { mutableStateOf(ArticleFilter.ReadingList) }

        fun push(s: Screen) { stack.add(s) }
        fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
        BackHandler(enabled = stack.size > 1) { pop() }

        // a shared link: save it and open it in the reader (it fills in when the save is done); Back goes to the list
        val repo = LocalContext.current.graph.repository
        // (collected, not keyed on the value: clearing it must not cancel a save halfway)
        LaunchedEffect(Unit) {
            snapshotFlow { shared.value }.filterNotNull().collect { text ->
                shared.value = null
                val id = when (val outcome = repo.saveFromText(text)) {
                    is SaveOutcome.Queued -> outcome.id
                    is SaveOutcome.AlreadySaved -> outcome.id
                    SaveOutcome.NotALink -> { Toast.makeText(this@MainActivity, "No link to save", Toast.LENGTH_SHORT).show(); return@collect }
                }
                filterState.value = ArticleFilter.ReadingList
                stack.clear()
                stack.addAll(listOf(Screen.Library, Screen.Reader(id)))
            }
        }

        when (val screen = stack.last()) {
            Screen.Library -> LibraryScreen(
                filter = filterState.value,
                onFilter = { filterState.value = it },
                onOpen = { push(Screen.Reader(it.id)) },
                onSettings = { push(Screen.Settings) },
            )
            is Screen.Reader -> key(screen.id) { ReaderScreen(screen.id, onBack = ::pop) }
            Screen.Settings -> SettingsScreen(onBack = ::pop)
        }
    }

    companion object {
        const val EXTRA_SHARED = "shared"
    }
}
