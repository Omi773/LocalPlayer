package app.localplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val APP_VERSION = "0.4.0"

@Composable
fun SettingsScreen(
    settings: Settings,
    onSettings: (Settings) -> Unit,
    sources: List<SourceInfo>,
    songs: List<Song>,
    scanning: Boolean,
    bridge: PlatformBridge,
    stats: Stats,
    onAddFolder: () -> Unit,
    onAddSongs: () -> Unit,
    onRemoveSource: (SourceInfo) -> Unit,
    onRescan: () -> Unit,
) {
    var page by remember { mutableStateOf("main") }
    when (page) {
        "stats" -> SubPage("Statistics", { page = "main" }) { StatsPage(stats, songs) }
        "logs" -> SubPage("Logs", { page = "main" }) { LogsPage() }
        "about" -> SubPage("About", { page = "main" }) { AboutPage() }
        else -> MainSettings(
            settings, onSettings, sources, songs.size, scanning, bridge,
            onAddFolder, onAddSongs, onRemoveSource, onRescan,
            go = { page = it },
        )
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSliderRow(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$title: $value", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun MainSettings(
    settings: Settings,
    onSettings: (Settings) -> Unit,
    sources: List<SourceInfo>,
    songCount: Int,
    scanning: Boolean,
    bridge: PlatformBridge,
    onAddFolder: () -> Unit,
    onAddSongs: () -> Unit,
    onRemoveSource: (SourceInfo) -> Unit,
    onRescan: () -> Unit,
    go: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val exts by produceState<List<ExtensionInfo>>(emptyList(), status) { value = bridge.extensions() }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        item { GroupTitle("Appearance") }
        item {
            GroupCard {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onSettings(settings.copy(themeMode = mode)) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                IntSliderRow("Cover corner radius", settings.coverRadius, 0..48) {
                    onSettings(settings.copy(coverRadius = it))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Font", style = MaterialTheme.typography.bodyLarge)
                Text("System font", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }

        item { GroupTitle("Playback") }
        item {
            GroupCard {
                ToggleRow("Automix", "Fade out and in between songs", settings.automix) {
                    onSettings(settings.copy(automix = it))
                }
                if (settings.automix) {
                    IntSliderRow("Fade length (seconds)", settings.fadeSec, 1..12) {
                        onSettings(settings.copy(fadeSec = it))
                    }
                }
                ToggleRow("Music haptics", "Vibrate with the beat while the screen is on", settings.haptics) {
                    onSettings(settings.copy(haptics = it))
                }
            }
        }

        item { GroupTitle("Now playing") }
        item {
            GroupCard {
                ToggleRow("Fullscreen cover by default", "Open Now Playing with the cover filling the screen", settings.fullscreenCover) {
                    onSettings(settings.copy(fullscreenCover = it))
                }
                ToggleRow("File and output details", "Show format and live audio output info", settings.showDetails) {
                    onSettings(settings.copy(showDetails = it))
                }
            }
        }

        item { GroupTitle("Lyrics") }
        item {
            GroupCard {
                ToggleRow("Online lyrics", "Look up lyrics with extensions when no .lrc is found", settings.onlineLyrics) {
                    onSettings(settings.copy(onlineLyrics = it))
                }
                ToggleRow("Word by word highlight", "Needs enhanced .lrc files", settings.wordSync) {
                    onSettings(settings.copy(wordSync = it))
                }
                ToggleRow("Centre text", null, settings.lyricsCenter) {
                    onSettings(settings.copy(lyricsCenter = it))
                }
                IntSliderRow("Text size", settings.lyricsSize, 16..40) {
                    onSettings(settings.copy(lyricsSize = it))
                }
            }
        }

        item { GroupTitle("Library") }
        item {
            GroupCard {
                Text(
                    if (scanning) "Scanning…" else "$songCount songs in library",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Music in the app's Files folder is always included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                sources.forEach { src ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (src.isFolder) Icons.Rounded.Folder else Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = colors.primary,
                        )
                        Text(
                            src.name,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onRemoveSource(src) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove")
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onAddFolder) { Text("Add folder") }
                    FilledTonalButton(onClick = onAddSongs) { Text("Add songs") }
                }
                TextButton(onClick = onRescan, enabled = !scanning) { Text("Rescan library") }
            }
        }

        item { GroupTitle("Extensions") }
        item {
            GroupCard {
                exts.forEach {
                    Text(
                        "${it.name}  -  ${it.type}  (${it.source})",
                        modifier = Modifier.padding(vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ActionRow("Create sample extension files", "Adds examples to Files > LocalPlayer > Extensions") {
                    scope.launch { status = bridge.installSampleExtensions() }
                }
            }
        }

        item { GroupTitle("Backup") }
        item {
            GroupCard {
                ActionRow("Back up settings and stats", "Saved to Files > LocalPlayer > Backups") {
                    scope.launch { status = bridge.backup() }
                }
                ActionRow("Restore from backup") {
                    scope.launch { status = bridge.restore() }
                }
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }

        item { GroupTitle("More") }
        item {
            GroupCard {
                ActionRow("Statistics") { go("stats") }
                ActionRow("Logs") { go("logs") }
                ActionRow("About") { go("about") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable
private fun StatsPage(stats: Stats, songs: List<Song>) {
    val colors = MaterialTheme.colorScheme
    val data = remember { stats.all() }
    val rows = data.entries.mapNotNull { (k, v) -> songs.firstOrNull { it.key() == k }?.let { it to v } }
    val plays = data.values.sumOf { it.first }
    val ms = data.values.sumOf { it.second }
    val artists = rows.filter { it.first.artist.isNotBlank() }
        .groupBy { it.first.artist }
        .mapValues { e -> e.value.sumOf { it.second.second } }
        .entries.sortedByDescending { it.value }.take(5)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Listening time", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(formatDuration(ms), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("$plays plays", color = colors.onSurfaceVariant)
            GroupTitle("Top songs")
        }
        items(rows.sortedByDescending { it.second.second }.take(10)) { (s, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
                Text("${v.first}x · ${formatDuration(v.second)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { GroupTitle("Top artists") }
        items(artists) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.key, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDuration(e.value), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    "Play songs for 30 seconds or more and they will show up here.",
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LogsPage() {
    val lines by AppLog.lines.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Also saved as localplayer.log in Files > LocalPlayer",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(lines.reversed()) {
                Text(it, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AboutPage() {
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text("LocalPlayer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Version $APP_VERSION", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("A music player for the songs stored on your iPhone.", Modifier.padding(top = 16.dp))
        Text(
            "Audio engine: AVAudioEngine with a 10-band parametric equalizer. Look inspired by PixelPlayer.",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
