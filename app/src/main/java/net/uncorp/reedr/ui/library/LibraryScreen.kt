package net.uncorp.reedr.ui.library

import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.uncorp.reedr.Blue
import net.uncorp.reedr.Line
import net.uncorp.reedr.OnBlue
import net.uncorp.reedr.data.Article
import net.uncorp.reedr.data.ArticleFilter
import net.uncorp.reedr.data.SaveOutcome
import net.uncorp.reedr.graph
import net.uncorp.reedr.save.Urls
import net.uncorp.reedr.ui.ArticleList
import net.uncorp.reedr.ui.ConfirmDialog
import net.uncorp.reedr.ui.OverflowMenu
import net.uncorp.reedr.ui.TextInputDialog
import net.uncorp.reedr.ui.rememberArticleActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    filter: ArticleFilter,
    onFilter: (ArticleFilter) -> Unit,
    onOpen: (Article) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val repo = graph.repository
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    val prefs by graph.settings.reader.collectAsStateWithLifecycle()
    // History switched off in Settings while it was the open list
    LaunchedEffect(prefs.keepHistory, filter) {
        if (!prefs.keepHistory && filter == ArticleFilter.History) onFilter(ArticleFilter.ReadingList)
    }

    val articlesFlow = remember(filter, searching, query) {
        if (searching && query.isNotBlank()) repo.search(filter, query) else repo.articles(filter)
    }
    val articles by articlesFlow.collectAsStateWithLifecycle(null)
    val (actions, dialogs) = rememberArticleActions(onOpen)

    BackHandler(enabled = searching || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else { searching = false; query = "" }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Text("Reedr", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                    fun pick(f: ArticleFilter) { onFilter(f); searching = false; scope.launch { drawer.close() } }
                    ArticleFilter.entries.filter { it != ArticleFilter.History || prefs.keepHistory }.forEach { f ->
                        NavigationDrawerItem(label = { Text(f.title) }, selected = filter == f, onClick = { pick(f) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                    NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = { scope.launch { drawer.close() }; onSettings() })
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (searching) {
                    val focus = remember { FocusRequester() }
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                        title = {
                            TextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text("Search ${filter.title}") },
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        },
                        actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                } else {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                        title = { Text(filter.title) },
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                            if (!articles.isNullOrEmpty()) {
                                OverflowMenu((if (filter == ArticleFilter.History) "Clear history" else "Delete all") to { confirm = true })
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (!searching && filter == ArticleFilter.ReadingList) {
                    FloatingActionButton(onClick = { adding = true }, containerColor = Blue, contentColor = OnBlue) { Icon(Icons.Default.Add, "Add a link") }
                }
            },
        ) { padding ->
            articles?.let { list ->
                val empty = when {
                    searching && query.isNotBlank() -> "Nothing in ${filter.title} matches “$query”"
                    filter == ArticleFilter.ReadingList -> "Nothing to read yet.\n\nShare a link from any app to “Save to Reedr”, or tap + to paste one."
                    filter == ArticleFilter.Archive -> "Archive articles you're done with from their ⋮ menu."
                    else -> "Articles you open appear here."
                }
                ArticleList(list, actions, empty, Modifier.padding(padding))
            }
        }
    }
    dialogs()

    if (confirm) {
        if (filter == ArticleFilter.History) {
            ConfirmDialog("Clear history?", "The articles stay saved; only the list of what you opened is cleared.", "Clear", onDismiss = { confirm = false }) {
                confirm = false
                scope.launch { repo.clearHistory() }
            }
        } else {
            val count = articles?.size ?: 0
            ConfirmDialog(
                "Delete all in “${filter.title}”?",
                if (count == 1) "1 article and its highlights will be deleted." else "$count articles and their highlights will be deleted.",
                "Delete all", onDismiss = { confirm = false },
            ) {
                confirm = false
                scope.launch { repo.deleteAll(filter) }
            }
        }
    }

    if (adding) {
        val clip = remember {
            context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()?.let(Urls::findUrl).orEmpty()
        }
        TextInputDialog("Save a link", clip, "https://…", "Save", KeyboardType.Uri, onDismiss = { adding = false }) { text ->
            adding = false
            scope.launch {
                val message = when (repo.saveFromText(text)) {
                    is SaveOutcome.Queued -> null
                    is SaveOutcome.AlreadySaved -> "Already saved, moved to the top"
                    SaveOutcome.NotALink -> "That isn't a web link"
                }
                message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
