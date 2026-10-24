package app.localplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(
    bridge: PlatformBridge,
    player: AudioPlayer,
    routePicker: @Composable (Modifier) -> Unit,
) {
    var settings by remember { mutableStateOf(decodeSettings(bridge.readText("settings.txt"))) }
    LocalPlayerTheme(settings.themeMode) {
        AppContent(
            bridge = bridge,
            player = player,
            settings = settings,
            onSettings = {
                settings = it
                bridge.writeText("settings.txt", it.encode())
            },
            routePicker = routePicker,
        )
    }
}

@Composable
private fun AppContent(
    bridge: PlatformBridge,
    player: AudioPlayer,
    settings: Settings,
    onSettings: (Settings) -> Unit,
    routePicker: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cache = remember { ArtCache(bridge) }
    val stats = remember { Stats(bridge.store) }
    val state by player.state.collectAsState()

    var songs by remember { mutableStateOf(bridge.loadCachedLibrary()) }
    var scanning by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(bridge.sources()) }
    var tab by remember { mutableStateOf(NavTab.HOME) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var eq by remember { mutableStateOf(decodeEq(bridge.store.get("eq"))) }

    LaunchedEffect(eq) {
        player.setEq(eq)
        bridge.store.put("eq", encodeEq(eq))
    }
    LaunchedEffect(settings.automix, settings.fadeSec) { player.setAutomix(settings.automix, settings.fadeSec) }
    LaunchedEffect(settings.haptics) { player.setHaptics(settings.haptics) }
    LaunchedEffect(Unit) { player.onListened = { s, ms -> stats.record(s.key(), ms) } }

    fun rescan() {
        if (scanning) return
        scope.launch {
            scanning = true
            try {
                songs = bridge.scan()
                sources = bridge.sources()
            } catch (t: Throwable) {
                AppLog.e("Library scan failed: ${t.message}")
            }
            scanning = false
        }
    }

    fun addFolder() {
        scope.launch { if (bridge.pickFolder()) rescan() }
    }

    fun addSongs() {
        scope.launch { if (bridge.pickSongs()) rescan() }
    }

    LaunchedEffect(Unit) { rescan() }

    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    NavTab.HOME -> HomeScreen(
                        songs, scanning, state, player, cache,
                        onAddFolder = ::addFolder,
                        onAddSongs = ::addSongs,
                        onInfo = { infoSong = it },
                    )
                    NavTab.SEARCH -> SearchScreen(songs, state, player, cache) { infoSong = it }
                    NavTab.LIBRARY -> LibraryScreen(
                        songs, scanning, state, player, cache,
                        onRescan = ::rescan,
                        onInfo = { infoSong = it },
                    )
                    NavTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSettings = onSettings,
                        sources = sources,
                        songs = songs,
                        scanning = scanning,
                        bridge = bridge,
                        stats = stats,
                        onAddFolder = ::addFolder,
                        onAddSongs = ::addSongs,
                        onRemoveSource = {
                            bridge.removeSource(it.id)
                            sources = bridge.sources()
                            rescan()
                        },
                        onRescan = ::rescan,
                    )
                }
            }
            val current = state.song
            if (current != null) {
                MiniPlayer(state, current, player, cache) { nowPlayingOpen = true }
            }
            FloatingNavBar(tab) { tab = it }
        }

        val current = state.song
        if (current != null) {
            AnimatedVisibility(
                visible = nowPlayingOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                NowPlayingScreen(
                    song = current,
                    state = state,
                    player = player,
                    bridge = bridge,
                    settings = settings,
                    cache = cache,
                    eq = eq,
                    onEq = { eq = it },
                    routePicker = routePicker,
                    onClose = { nowPlayingOpen = false },
                    onSongUpdated = { s -> songs = songs.map { if (it.path == s.path) s else it } },
                )
            }
        }

        val info = infoSong
        if (info != null) SongInfoDialog(info) { infoSong = null }

        ErrorBanner()
    }
}

@Composable
private fun BoxScope.ErrorBanner() {
    val err by AppLog.error.collectAsState()
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(err) {
        val e = err
        if (e != null) {
            shown = e
            delay(4500)
            AppLog.dismissError()
        }
    }
    AnimatedVisibility(
        visible = err != null,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                shown,
                Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
