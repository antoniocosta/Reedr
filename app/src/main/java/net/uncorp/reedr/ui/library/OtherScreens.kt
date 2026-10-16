package net.uncorp.reedr.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.uncorp.reedr.BuildConfig
import net.uncorp.reedr.Blue
import net.uncorp.reedr.Ink
import net.uncorp.reedr.Line
import net.uncorp.reedr.Muted
import net.uncorp.reedr.OnBlue
import net.uncorp.reedr.ThemePicker
import net.uncorp.reedr.ui.reader.ReaderWebView
import net.uncorp.reedr.data.ReaderFont
import net.uncorp.reedr.data.ReaderPrefs
import net.uncorp.reedr.data.ReaderTheme
import net.uncorp.reedr.graph
import net.uncorp.reedr.ui.ConfirmDialog
import net.uncorp.reedr.ui.EmptyState
import net.uncorp.reedr.ui.OverflowMenu
import net.uncorp.reedr.ui.Pill
import net.uncorp.reedr.ui.TextInputDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        content = content,
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val settings = graph.settings
    val repo = graph.repository
    val scope = rememberCoroutineScope()
    val prefs by settings.reader.collectAsStateWithLifecycle()
    var licenses by remember { mutableStateOf(false) }
    SubScreen("Settings", onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section("Reader")
            ReaderStyleControls(prefs) { settings.update(it) }
            Section("Saving")
            SwitchRow("Download images", "Store pictures with each article so they show offline", prefs.downloadImages) { v -> settings.update { it.copy(downloadImages = v) } }
            Section("Library")
            SwitchRow("Archive at the end", "Move an article to the Archive when you scroll to its end", prefs.archiveAtEnd) { v -> settings.update { it.copy(archiveAtEnd = v) } }
            SwitchRow("Keep history", "List the articles you open. Turning it off clears the list", prefs.keepHistory) { v ->
                settings.update { it.copy(keepHistory = v) }
                if (!v) scope.launch { repo.clearHistory() }
            }
            Section("About")
            Text("No accounts, no ads, no analytics. Pages are downloaded straight from the site and stored only on this phone.", color = Muted, fontSize = 13.sp)
            Text("Open-source licenses", color = Blue, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { licenses = true }.padding(vertical = 12.dp))
            HorizontalDivider(color = Line)
            ThemePicker()
            Footer()
        }
    }

    if (licenses) AlertDialog(
        onDismissRequest = { licenses = false },
        confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } },
        title = { Text("Open-source licenses") },
        text = {
            Text(
                "Readability.js (Mozilla) — Apache License 2.0\n" +
                    "jsoup — MIT License\n" +
                    "OkHttp, Okio — Apache License 2.0\n" +
                    "Kotlin, kotlinx.coroutines — Apache License 2.0\n" +
                    "AndroidX, Jetpack Compose, Room, WorkManager — Apache License 2.0",
            )
        },
    )
}

@Composable
fun ReaderStyleControls(prefs: ReaderPrefs, update: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    // "Auto" follows the app theme (Theme, at the bottom of Settings)
    ChoiceRow("Page") {
        ReaderTheme.entries.forEach { t ->
            val label = if (t == ReaderTheme.SYSTEM) "Auto" else t.name.lowercase().replaceFirstChar(Char::titlecase)
            Pill(label, prefs.theme == t) { update { it.copy(theme = t) } }
        }
    }
    ChoiceRow("Font") {
        Pill("Serif", prefs.font == ReaderFont.SERIF) { update { it.copy(font = ReaderFont.SERIF) } }
        Pill("Sans", prefs.font == ReaderFont.SANS) { update { it.copy(font = ReaderFont.SANS) } }
    }
    ValueRow("Text size", "%.0f".format(prefs.fontSize))
    Slider(
        value = prefs.fontSize, onValueChange = { v -> update { it.copy(fontSize = v) } },
        valueRange = ReaderWebView.MIN_TEXT_SIZE..ReaderWebView.MAX_TEXT_SIZE,
    )
    ValueRow("Line spacing", "%.1f".format(prefs.lineHeight))
    Slider(value = prefs.lineHeight, onValueChange = { v -> update { it.copy(lineHeight = (v * 10).toInt() / 10f) } }, valueRange = 1.2f..2.2f, steps = 9)
}

/** "Label   pill pill pill", laid out like the collection's theme picker. */
@Composable
private fun ChoiceRow(label: String, pills: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        pills()
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Muted, fontSize = 13.sp)
    }
}

/** "uncorp.net · v0.1.0" — same footer in every app of the collection; tap opens the site. */
@Composable
private fun Footer() {
    val context = LocalContext.current
    Text(
        "uncorp.net · v${BuildConfig.VERSION_NAME}",
        modifier = Modifier.fillMaxWidth()
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://uncorp.net"))) }
            .padding(vertical = 20.dp),
        color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = Line, modifier = Modifier.padding(top = 12.dp))
    Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    }
}
