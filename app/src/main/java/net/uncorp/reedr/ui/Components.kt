package net.uncorp.reedr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.uncorp.reedr.data.Article
import net.uncorp.reedr.data.SaveStatus
import net.uncorp.reedr.Blue
import net.uncorp.reedr.Ink
import net.uncorp.reedr.Line
import net.uncorp.reedr.Muted
import net.uncorp.reedr.OnBlue
import net.uncorp.reedr.Tint
import net.uncorp.reedr.graph
import java.io.File

// ---------- thumbnails (decoded at ~240 px, cached in memory)

private val thumbCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
fun Thumbnail(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState(thumbCache.get(file.path), file.path) {
        if (value == null) value = withContext(Dispatchers.IO) { decodeThumb(file) }?.also { thumbCache.put(file.path, it) }
    }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

private fun decodeThumb(file: File, target: Int = 240): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

// ---------- article list

data class ArticleActions(
    val open: (Article) -> Unit,
    val toggleArchived: (Article) -> Unit,
    val delete: (Article) -> Unit,
    val retry: (Article) -> Unit,
)

@Composable
fun rememberArticleActions(open: (Article) -> Unit): Pair<ArticleActions, @Composable () -> Unit> {
    val context = LocalContext.current
    val repo = context.graph.repository
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Article?>(null) }
    val actions = remember(open) {
        ArticleActions(
            open = open,
            // from the reading list this moves the article to the Archive; in History it stays listed there too
            toggleArchived = { a -> scope.launch { repo.setArchived(a.id, !a.archived) } },
            delete = { deleting = it },
            retry = { a -> scope.launch { repo.retry(a.id) } },
        )
    }
    val dialogs: @Composable () -> Unit = {
        deleting?.let { a ->
            ConfirmDialog("Delete article?", "“${a.title}” and its highlights will be removed.", "Delete", onDismiss = { deleting = null }) {
                scope.launch { repo.delete(a.id) }
                deleting = null
            }
        }
    }
    return actions to dialogs
}

@Composable
fun ArticleList(articles: List<Article>, actions: ArticleActions, empty: String, modifier: Modifier = Modifier) {
    if (articles.isEmpty()) {
        EmptyState(empty, modifier)
        return
    }
    // pinch the list to change its text size (saved when the fingers lift); one finger still scrolls
    val settings = LocalContext.current.graph.settings
    val saved by settings.reader.collectAsStateWithLifecycle()
    var scale by remember(saved.listScale) { mutableFloatStateOf(saved.listScale) }
    LazyColumn(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var pinched = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) scale = (scale * zoom).coerceIn(MIN_LIST_SCALE, MAX_LIST_SCALE)
                        event.changes.forEach { it.consume() }
                        pinched = true
                    }
                } while (event.changes.any { it.pressed })
                if (pinched) settings.update { it.copy(listScale = scale) }
            }
        },
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(articles, key = { it.id }) { a ->
            ArticleRow(a, actions, scale)
            HorizontalDivider(Modifier.padding(start = 16.dp), color = Line)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(a: Article, actions: ArticleActions, scale: Float = 1f) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { if (a.status == SaveStatus.READY) actions.open(a) else if (a.status == SaveStatus.FAILED) actions.retry(a) }, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Ink, fontSize = 16.sp * scale, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val line = when (a.status) {
                SaveStatus.SAVING -> "Saving…"
                SaveStatus.FAILED -> "Couldn't save: ${a.error ?: "unknown error"} · tap to retry"
                SaveStatus.READY -> listOfNotNull(a.siteName, a.readingMinutes.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · ")
            }
            Text(line, fontSize = 13.sp * scale, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (a.status == SaveStatus.FAILED) Blue else Muted)
            if (a.status == SaveStatus.SAVING) {
                Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.width(120.dp))
            } else if (a.progress > 0.02f && a.progress < 0.97f && !a.archived) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { a.progress }, modifier = Modifier.width(120.dp), drawStopIndicator = {})
            }
        }
        a.leadImage?.let { path ->
            Spacer(Modifier.width(12.dp))
            Thumbnail(File(context.graph.files.dir(a.id), path), Modifier.size(72.dp * scale).clip(RoundedCornerShape(6.dp)))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (a.archived) "Move to reading list" else "Archive") }, onClick = { menu = false; actions.toggleArchived(a) })
                DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; shareText(context, a.url, a.title) })
                DropdownMenuItem(text = { Text("Open original") }, onClick = { menu = false; openUrl(context, a.url) })
                if (a.status == SaveStatus.FAILED) DropdownMenuItem(text = { Text("Try again") }, onClick = { menu = false; actions.retry(a) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; actions.delete(a) })
            }
        }
    }
}

/** A choice pill like the collection's theme picker: blue when selected, pale fill otherwise. */
@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .background(if (selected) Blue else Tint, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) OnBlue else Blue,
        fontSize = 13.sp,
    )
}

/** The ⋮ button of a top bar with its menu items (label to action). */
@Composable
fun OverflowMenu(vararg items: Pair<String, () -> Unit>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 15.sp)
    }
}

// ---------- dialogs

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, confirm: String, keyboardType: KeyboardType = KeyboardType.Text, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_LIST_SCALE = 0.8f
private const val MAX_LIST_SCALE = 1.6f

// ---------- intents

fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    context.startActivity(Intent.createChooser(send, null))
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        .onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Reedr", text))
}

