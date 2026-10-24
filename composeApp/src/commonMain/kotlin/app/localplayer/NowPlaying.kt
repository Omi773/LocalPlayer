package app.localplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TAB_NAMES = listOf("Player", "Lyrics", "Info", "EQ")

@Composable
fun NowPlayingScreen(
    song: Song,
    state: PlaybackState,
    player: AudioPlayer,
    bridge: PlatformBridge,
    settings: Settings,
    cache: ArtCache,
    eq: EqState,
    onEq: (EqState) -> Unit,
    routePicker: @Composable (Modifier) -> Unit,
    onClose: () -> Unit,
    onSongUpdated: (Song) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val out by bridge.output.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            bridge.refreshOutput()
            delay(2000)
        }
    }
    var fullCover by remember { mutableStateOf(settings.fullscreenCover) }
    var tab by remember { mutableStateOf(0) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        if (fullCover) {
            val white = Color.White
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Artwork(
                    song.path, cache, Modifier.fillMaxSize(), RectangleShape,
                    placeholder = Color(0xFF1B1B1B),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0x99000000), Color.Transparent, Color(0xF2000000)),
                        ),
                    ),
                )
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = white, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        RouteButton(routePicker, white)
                        IconButton(onClick = { fullCover = false }) {
                            Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit fullscreen cover", tint = white)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = white, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = white.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(qualityLine(song, out), style = MaterialTheme.typography.labelMedium, color = white.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
                    WavySeekBar(
                        progress, state.isPlaying,
                        onSeek = { player.seekTo((it * duration).toLong()) },
                        modifier = Modifier.padding(top = 8.dp),
                        activeColor = colors.primary,
                        trackColor = white.copy(alpha = 0.3f),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium, color = white.copy(alpha = 0.8f))
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = white.copy(alpha = 0.8f))
                    }
                    Spacer(Modifier.height(8.dp))
                    PlayerControls(state, player, white, colors.primary, colors.onPrimary)
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(32.dp))
                    }
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    RouteButton(routePicker, colors.onSurface)
                    IconButton(onClick = { fullCover = true }) {
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "Fullscreen cover")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TAB_NAMES.forEachIndexed { i, name ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(name) })
                    }
                }
                Crossfade(tab, Modifier.weight(1f), animationSpec = tween(250)) { t ->
                    when (t) {
                        0 -> PlayerTab(song, state, player, settings, cache, out, progress, duration)
                        1 -> LyricsTab(bridge, player, state, song, settings)
                        2 -> InfoTab(bridge, out, song, onSongUpdated)
                        else -> EqTab(eq, onEq)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteButton(routePicker: @Composable (Modifier) -> Unit, tint: Color) {
    Box(Modifier.size(48.dp), Alignment.Center) {
        Icon(Icons.Rounded.Cast, contentDescription = "Output device", tint = tint)
        // Invisible AirPlay picker on top: tap to choose the output.
        routePicker(Modifier.fillMaxSize())
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackState,
    player: AudioPlayer,
    content: Color,
    accent: Color,
    onAccent: Color,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { player.toggleShuffle() }) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "Shuffle",
                tint = if (state.shuffle) accent else content.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = content, modifier = Modifier.size(34.dp))
        }
        Box(
            Modifier
                .size(width = 88.dp, height = 64.dp)
                .clip(RoundedCornerShape(if (state.isPlaying) 22.dp else 32.dp))
                .background(accent)
                .clickable { player.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play or pause",
                tint = onAccent,
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = { player.next() }, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Next",
                tint = if (state.hasNext) content else content.copy(alpha = 0.3f),
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                tint = if (state.repeat == RepeatMode.OFF) content.copy(alpha = 0.6f) else accent,
            )
        }
    }
}

// ---------- Player tab ----------

@Composable
private fun PlayerTab(
    song: Song,
    state: PlaybackState,
    player: AudioPlayer,
    settings: Settings,
    cache: ArtCache,
    out: OutputInfo,
    progress: Float,
    duration: Long,
) {
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.9f, tween(350), label = "cover")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Artwork(
            song.path, cache,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(vertical = 8.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            RoundedCornerShape(settings.coverRadius.dp),
        )
        Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (settings.showDetails) {
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(qualityLine(song, out), style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
            }
        }
        WavySeekBar(
            progress, state.isPlaying,
            onSeek = { player.seekTo((it * duration).toLong()) },
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        PlayerControls(state, player, colors.onSurface, colors.primary, colors.onPrimary)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- Lyrics tab ----------

@Composable
private fun LyricsTab(
    bridge: PlatformBridge,
    player: AudioPlayer,
    state: PlaybackState,
    song: Song,
    settings: Settings,
) {
    val colors = MaterialTheme.colorScheme
    val res by produceState<Pair<Boolean, LyricsResult?>>(true to null, song.path, settings.onlineLyrics) {
        value = true to null
        value = false to bridge.lyrics(song, settings.onlineLyrics)
    }
    val loading = res.first
    val lyrics = res.second
    if (loading) {
        Text("Looking for lyrics…", color = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        return
    }
    if (lyrics == null) {
        Column(Modifier.padding(8.dp)) {
            Text("No lyrics found.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Put a .lrc file with the same name next to the song, or turn on Online lyrics in Settings.",
                Modifier.padding(top = 8.dp),
                color = colors.onSurfaceVariant,
            )
        }
        return
    }
    val idx = if (lyrics.synced) currentLine(lyrics.lines, state.positionMs) else -1
    val list = rememberLazyListState()
    LaunchedEffect(idx) {
        if (idx >= 0) list.animateScrollToItem((idx - 2).coerceAtLeast(0))
    }
    val center = settings.lyricsCenter
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = list) {
            itemsIndexed(lyrics.lines) { i, line ->
                val active = i == idx
                val a by animateFloatAsState(if (active || !lyrics.synced) 1f else 0.4f, tween(300), label = "lyricAlpha")
                val sc by animateFloatAsState(if (active) 1f else 0.94f, tween(300), label = "lyricScale")
                val on = colors.primary
                val dim = colors.onSurface.copy(alpha = 0.55f)
                val text = if (active && settings.wordSync && line.words.isNotEmpty()) {
                    buildAnnotatedString {
                        line.words.forEach { w ->
                            withStyle(SpanStyle(color = if (state.positionMs >= w.timeMs) on else dim)) { append(w.text) }
                        }
                    }
                } else {
                    buildAnnotatedString { append(line.text) }
                }
                Text(
                    text,
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = a
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(if (center) 0.5f else 0f, 0.5f)
                        }
                        .clickable(enabled = lyrics.synced) { player.seekTo(line.timeMs) }
                        .padding(vertical = 8.dp),
                    color = if (active && line.words.isEmpty()) on else colors.onSurface,
                    fontSize = settings.lyricsSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start,
                )
            }
        }
        Text(
            "Source: ${lyrics.source}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

