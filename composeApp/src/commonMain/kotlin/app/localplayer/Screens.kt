package app.localplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class NavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    SETTINGS("Settings", Icons.Rounded.SettingsIcon),
}

@Composable
fun FloatingNavBar(selected: NavTab, onSelect: (NavTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = colors.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavTab.entries.forEach { tab ->
                val sel = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (sel) colors.primaryContainer else Color.Transparent)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (sel) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    state: PlaybackState,
    song: Song,
    player: AudioPlayer,
    cache: ArtCache,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHighest,
    ) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.path, cache, Modifier.size(46.dp), RoundedCornerShape(14.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { player.togglePlayPause() }) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play or pause",
                    )
                }
                IconButton(onClick = { player.next() }, enabled = state.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(colors.onSurface.copy(alpha = 0.12f)),
            ) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.primary))
            }
        }
    }
}

@Composable
fun EmptyLibrary(onAddFolder: () -> Unit, onAddSongs: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("No music yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a music folder from Files, or choose individual songs. Files in the app's own Files folder are found automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddFolder) { Text("Add music folder") }
        FilledTonalButton(onClick = onAddSongs) { Text("Add songs") }
    }
}

@Composable
fun HomeScreen(
    songs: List<Song>,
    scanning: Boolean,
    state: PlaybackState,
    player: AudioPlayer,
    cache: ArtCache,
    onAddFolder: () -> Unit,
    onAddSongs: () -> Unit,
    onInfo: (Song) -> Unit,
) {
    val picks = remember(songs) { songs.shuffled().take(8) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Text("Your Mix", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (scanning) "Scanning your library…" else "${songs.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (songs.isEmpty()) {
            if (!scanning) item { EmptyLibrary(onAddFolder, onAddSongs) }
        } else {
            item {
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { player.playShuffled(songs) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle all")
                    }
                    FilledTonalButton(onClick = { player.play(songs, 0) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play all")
                    }
                }
            }
            item {
                Text(
                    "Quick picks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(picks, key = { _, s -> s.path }) { index, song ->
                SongRow(
                    song,
                    current = state.song?.path == song.path,
                    playing = state.isPlaying,
                    cache = cache,
                    onClick = { player.play(picks, index) },
                    onInfo = { onInfo(song) },
                )
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    state: PlaybackState,
    player: AudioPlayer,
    cache: ArtCache,
    onInfo: (Song) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (header != null) item { header() }
        itemsIndexed(songs, key = { _, s -> s.path }) { index, song ->
            SongRow(
                song,
                current = state.song?.path == song.path,
                playing = state.isPlaying,
                cache = cache,
                onClick = { player.play(songs, index) },
                onInfo = { onInfo(song) },
            )
        }
    }
}

@Composable
fun LibraryScreen(
    songs: List<Song>,
    scanning: Boolean,
    state: PlaybackState,
    player: AudioPlayer,
    cache: ArtCache,
    onRescan: () -> Unit,
    onInfo: (Song) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val albums = remember(songs) {
        songs.groupBy { it.album.ifBlank { "Unknown album" } }.toList().sortedBy { it.first.lowercase() }
    }
    val artists = remember(songs) {
        songs.groupBy { it.artist.ifBlank { "Unknown artist" } }.toList().sortedBy { it.first.lowercase() }
    }

    val d = detail
    if (d != null) {
        val list = if (d.first == "album") {
            songs.filter { it.album.ifBlank { "Unknown album" } == d.second }
        } else {
            songs.filter { it.artist.ifBlank { "Unknown artist" } == d.second }
        }
        Column {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detail = null }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Back", modifier = Modifier.size(28.dp))
                }
                Text(
                    d.second,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { player.playShuffled(list) }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle")
                }
            }
            SongList(list, state, player, cache, onInfo)
        }
        return
    }

    Column {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRescan, enabled = !scanning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Songs", "Albums", "Artists").forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
            }
        }
        when (tab) {
            0 -> SongList(songs, state, player, cache, onInfo)
            1 -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(albums, key = { it.first }) { (name, list) ->
                    GroupRow(
                        name,
                        "${list.size} songs",
                        list.first().path,
                        cache,
                        RoundedCornerShape(14.dp),
                    ) { detail = "album" to name }
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(artists, key = { it.first }) { (name, list) ->
                    GroupRow(
                        name,
                        "${list.size} songs",
                        list.first().path,
                        cache,
                        CircleShape,
                    ) { detail = "artist" to name }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    songs: List<Song>,
    state: PlaybackState,
    player: AudioPlayer,
    cache: ArtCache,
    onInfo: (Song) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }.take(200)
    }
    Column {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SongList(results, state, player, cache, onInfo)
    }
}

@Composable
fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("Artist", song.artist.ifBlank { "—" })
                DetailRow("Album", song.album.ifBlank { "—" })
                FileDetailRows(song)
            }
        },
    )
}
