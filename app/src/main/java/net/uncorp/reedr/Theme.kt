package net.uncorp.reedr

import android.content.Context
import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Light/dark theme shared by the apps of the collection (same file in each app):
 * flat electric blue + ink on paper, inverted for dark. The user picks Auto (follow the system),
 * Light or Dark.
 */

enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Light"), DARK("Dark") }

data class Palette(
    val paper: Color,     // background
    val ink: Color,       // text
    val muted: Color,     // secondary text
    val accent: Color,    // the electric blue
    val onAccent: Color,  // text on the blue
    val tint: Color,      // pale blue fill (unselected chips, notes)
    val line: Color,      // dividers, outlines
    val dark: Boolean,
)

// Only black, white and the accent (#0000EE; #8AB4F8 in dark mode, on #202124). Greys are black or white at
// lower opacity; no tinted (purple-looking) backgrounds
val LightPalette = Palette(
    paper = Color.White, ink = Color.Black, muted = Color(0x99000000), accent = Color(0xFF0000EE),
    onAccent = Color.White, tint = Color(0x0F000000), line = Color(0x22000000), dark = false,
)
val DarkPalette = Palette(
    paper = Color(0xFF202124), ink = Color.White, muted = Color(0x99FFFFFF), accent = Color(0xFF8AB4F8),
    onAccent = Color(0xFF202124), tint = Color(0x1FFFFFFF), line = Color(0x33FFFFFF), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.paper
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val OnBlue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
val Tint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.tint
val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line

private val Context.ui by preferencesDataStore("ui")
private val THEME = stringPreferencesKey("theme")

object ThemeStore {
    fun flow(ctx: Context): Flow<ThemeMode> = ctx.applicationContext.ui.data.map { p ->
        ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: ThemeMode.AUTO
    }

    suspend fun set(ctx: Context, mode: ThemeMode) {
        ctx.applicationContext.ui.edit { it[THEME] = mode.name }
    }
}

/** Applies the chosen theme: palette, Material colours and status/navigation bar icons. */
@Composable
fun AppTheme(activity: ComponentActivity, content: @Composable () -> Unit) {
    // Nothing until the saved choice is read (a few ms): no first frame in the wrong theme
    val mode by remember { ThemeStore.flow(activity) }.collectAsState(initial = null)
    val dark = when (mode ?: return) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    LaunchedEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
    // Every Material colour set from the palette, so nothing falls back to Material's default purple
    // (dialogs, switches, text selection, containers)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.tint, onPrimaryContainer = p.accent,
        inversePrimary = p.accent,
        secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.tint, onSecondaryContainer = p.accent,
        tertiary = p.accent, onTertiary = p.onAccent, tertiaryContainer = p.tint, onTertiaryContainer = p.accent,
        background = p.paper, onBackground = p.ink,
        surface = p.paper, onSurface = p.ink, surfaceVariant = p.paper, onSurfaceVariant = p.muted,
        surfaceTint = p.paper, inverseSurface = p.ink, inverseOnSurface = p.paper,
        surfaceBright = p.paper, surfaceDim = p.paper, surfaceContainerLowest = p.paper, surfaceContainerLow = p.paper,
        surfaceContainer = p.paper, surfaceContainerHigh = p.paper, surfaceContainerHighest = p.paper,
        outline = p.muted, outlineVariant = p.line, scrim = Color.Black,
    )
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** "Theme   Auto  Light  Dark" — pills like the rest of the app. */
@Composable
fun ThemePicker() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by remember { ThemeStore.flow(ctx) }.collectAsState(initial = ThemeMode.AUTO)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Theme", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        ThemeMode.entries.forEach { m ->
            val selected = m == mode
            Text(
                m.label,
                modifier = Modifier
                    .background(if (selected) Blue else Tint, RoundedCornerShape(6.dp))
                    .clickable { scope.launch { ThemeStore.set(ctx, m) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (selected) OnBlue else Blue,
                fontSize = 13.sp,
            )
        }
    }
}
