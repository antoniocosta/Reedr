package net.uncorp.reedr.ui.reader

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import net.uncorp.reedr.LocalPalette
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.uncorp.reedr.data.Highlight
import net.uncorp.reedr.data.SaveOutcome
import net.uncorp.reedr.data.SaveStatus
import net.uncorp.reedr.graph
import net.uncorp.reedr.ui.ConfirmDialog
import net.uncorp.reedr.ui.EmptyState
import net.uncorp.reedr.ui.copyText
import net.uncorp.reedr.ui.library.ReaderStyleControls
import net.uncorp.reedr.ui.openUrl
import net.uncorp.reedr.ui.shareText
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(articleId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val repo = graph.repository
    val scope = rememberCoroutineScope()
    val article by repo.article(articleId).collectAsStateWithLifecycle(null)
    val highlights by repo.highlights(articleId).collectAsStateWithLifecycle(emptyList())
    val prefs by graph.settings.reader.collectAsStateWithLifecycle()
    val dark = LocalPalette.current.dark   // the app theme (Auto follows the system)

    var progress by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var styleSheet by remember { mutableStateOf(false) }
    var highlightList by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var tappedHighlight by remember { mutableStateOf<Highlight?>(null) }
    var tappedLink by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var missing by remember { mutableStateOf(false) }

    val currentArticle by rememberUpdatedState(article)
    val currentHighlights by rememberUpdatedState(highlights)
    val archiveAtEnd by rememberUpdatedState(prefs.archiveAtEnd)

    LaunchedEffect(articleId) { if (graph.settings.reader.value.keepHistory) repo.markOpened(articleId) }

    val callbacks = remember {
        object : ReaderCallbacks {
            override fun onProgress(fraction: Float) {
                progress = fraction
                scope.launch {
                    repo.setProgress(articleId, fraction)
                    val a = currentArticle
                    if (fraction >= 0.98f && archiveAtEnd && a != null && !a.archived) repo.setArchived(articleId, true)
                }
            }
            override fun onHighlightTap(id: Long) { tappedHighlight = currentHighlights.firstOrNull { it.id == id } }
            override fun onLinkTap(url: String) { tappedLink = url }
            override fun onTextSize(px: Float) = graph.settings.update { it.copy(fontSize = px) }
            override fun onHighlightSelection(json: String) {
                val o = JSONObject(json)
                scope.launch {
                    val text = o.getString("text")
                    val id = repo.addHighlight(articleId, text, o.optString("prefix"), o.optString("suffix"))
                    webView?.addHighlight(Highlight(id = id, articleId = articleId, text = text, prefix = o.optString("prefix"), suffix = o.optString("suffix")))
                }
            }
        }
    }

    // Load the page once per article; theme and text changes are applied live with CSS variables.
    LaunchedEffect(webView, articleId) {
        val web = webView ?: return@LaunchedEffect
        val a = repo.article(articleId).first { it == null || it.status == SaveStatus.READY } ?: return@LaunchedEffect
        val body = repo.content(articleId)
        if (body == null) { missing = true; return@LaunchedEffect }
        progress = a.progress
        val initialHighlights = repo.highlights(articleId).first()
        web.show(articleId, ReaderPage.build(a, body, initialHighlights, graph.settings.reader.value, dark))
    }
    LaunchedEffect(prefs, dark, webView) { webView?.applyStyle(prefs, dark) }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    val palette = ReaderWebView.palette(prefs, dark)
    val pageColor = Color(android.graphics.Color.parseColor(palette.bg))
    val inkColor = Color(android.graphics.Color.parseColor(palette.fg))

    Scaffold(
        containerColor = pageColor,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = pageColor, titleContentColor = inkColor, navigationIconContentColor = inkColor, actionIconContentColor = inkColor),
                    title = { Text(article?.siteName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        TextButton(onClick = { styleSheet = true }) { Text("Aa", color = inkColor, style = MaterialTheme.typography.titleMedium) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            val a = article
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (a != null) DropdownMenuItem(text = { Text(if (a.archived) "Move to reading list" else "Archive") }, onClick = {
                                    menu = false; scope.launch { repo.setArchived(articleId, !a.archived) }
                                })
                                DropdownMenuItem(text = { Text("Highlights (${highlights.size})") }, onClick = { menu = false; highlightList = true })
                                if (a != null) {
                                    DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; shareText(context, a.url, a.title) })
                                    DropdownMenuItem(text = { Text("Open original") }, onClick = { menu = false; openUrl(context, a.url) })
                                }
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
                            }
                        }
                    },
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {}, gapSize = 0.dp)
            }
        },
    ) { padding ->
        val a = article
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                missing -> EmptyState("This article's saved copy is missing. Delete it and save the link again.")
                a != null && a.status == SaveStatus.FAILED -> FailedState(a.error) { scope.launch { repo.retry(articleId) } }
                a != null && a.status == SaveStatus.SAVING -> EmptyState("Still saving…")
            }
            if (!missing && a?.status != SaveStatus.FAILED) {
                AndroidView(
                    factory = { ctx -> ReaderWebView(ctx, graph.files, callbacks).also { it.setBackgroundColor(android.graphics.Color.TRANSPARENT); webView = it } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (styleSheet) ModalBottomSheet(onDismissRequest = { styleSheet = false }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            ReaderStyleControls(prefs) { graph.settings.update(it) }
        }
    }

    if (highlightList) ModalBottomSheet(onDismissRequest = { highlightList = false }) {
        if (highlights.isEmpty()) {
            Text("Select text and choose “Highlight” to save a passage.", modifier = Modifier.padding(24.dp))
        } else LazyColumn(Modifier.navigationBarsPadding()) {
            items(highlights, key = { it.id }) { h ->
                Text(
                    "“${h.text.trim()}”", fontStyle = FontStyle.Italic, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { highlightList = false; webView?.scrollToHighlight(h.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    tappedHighlight?.let { h ->
        AlertDialog(
            onDismissRequest = { tappedHighlight = null },
            text = { Text("“${h.text.trim()}”", fontStyle = FontStyle.Italic, maxLines = 10, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deleteHighlight(h.id) }
                    webView?.removeHighlight(h.id); tappedHighlight = null
                }) { Text("Remove") }
            },
            dismissButton = {
                Box {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { copyText(context, h.text); tappedHighlight = null }) { Text("Copy") }
                        TextButton(onClick = { shareText(context, "“${h.text.trim()}” — ${article?.title.orEmpty()}\n${article?.url.orEmpty()}"); tappedHighlight = null }) { Text("Share") }
                    }
                }
            },
        )
    }

    tappedLink?.let { url ->
        AlertDialog(
            onDismissRequest = { tappedLink = null },
            title = { Text(url, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    tappedLink = null
                    scope.launch {
                        val msg = when (repo.saveFromText(url)) {
                            is SaveOutcome.Queued -> "Saving to Reedr"
                            is SaveOutcome.AlreadySaved -> "Already in Reedr"
                            SaveOutcome.NotALink -> "Can't save this link"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save to Reedr") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { copyText(context, url); tappedLink = null }) { Text("Copy") }
                    TextButton(onClick = { openUrl(context, url); tappedLink = null }) { Text("Open") }
                }
            },
        )
    }

    if (deleting) ConfirmDialog("Delete article?", "It will be removed with its highlights.", "Delete", onDismiss = { deleting = false }) {
        deleting = false
        scope.launch { repo.delete(articleId); onBack() }
    }
}

@Composable
private fun FailedState(error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text("Couldn't save this page", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(error ?: "Unknown error", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
