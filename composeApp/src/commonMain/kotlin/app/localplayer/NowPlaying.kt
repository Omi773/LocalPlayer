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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    var menuExpanded by remember { mutableStateOf(false) }
    var fullCover by remember { mutableStateOf(settings.fullscreenCover) }
    var tab by remember { mutableStateOf(0) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    LaunchedEffect(Unit) {
        while (true) {
            bridge.refreshOutput()
            delay(2000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        if (fullCover) {
            val white = Color.White
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Artwork(
                    song.path,
                    cache,
                    Modifier.fillMaxSize(),
                    RectangleShape,
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
                        progress,
                        state.isPlaying,
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
                    .padding(horizontal = 22.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(28.dp))
                    }
                    Text(
                        "Now Playing",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { /* queue */ }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Queue", modifier = Modifier.size(28.dp))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More", modifier = Modifier.size(26.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Queue") },
                                onClick = {
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Song info") },
                                onClick = {
                                    menuExpanded = false
                                    tab = 2
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("AirPlay") },
                                onClick = {
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TAB_NAMES.forEachIndexed { i, name ->
                        FilterChip(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(name) },
                            modifier = Modifier.weight(1f),
                        )
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
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { player.toggleShuffle() }, modifier = Modifier.size(54.dp)) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "Shuffle",
                tint = if (state.shuffle) accent else content.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = content, modifier = Modifier.size(34.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .height(78.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(accent)
                .clickable { player.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play or pause",
                tint = onAccent,
                modifier = Modifier.size(38.dp),
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
        IconButton(onClick = { player.cycleRepeat() }, modifier = Modifier.size(54.dp)) {
            Icon(
                if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                tint = if (state.repeat == RepeatMode.OFF) content.copy(alpha = 0.7f) else accent,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

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
    val scale by animateFloatAsState(if (state.isPlaying) 1f else 0.96f, tween(350), label = "cover")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Artwork(
            song.path,
            cache,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(vertical = 8.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            RoundedCornerShape(30.dp),
            placeholder = Color(0xFF1F1F1F),
        )

        Text(
            song.title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            song.artist.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (settings.showDetails) {
            Surface(
                color = colors.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        qualityLine(song, out),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                }
            }
        }

        WavySeekBar(
            progress,
            state.isPlaying,
            onSeek = { player.seekTo((it * duration).toLong()) },
            modifier = Modifier.padding(top = 18.dp),
            activeColor = colors.primary,
            trackColor = colors.outline.copy(alpha = 0.35f),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        PlayerControls(state, player, colors.onSurface, colors.primary, colors.onPrimary)
        Spacer(Modifier.height(14.dp))

        Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Output", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Text(
                    out.deviceName.ifBlank { "System" },
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.padding(start = 16.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun OutputDetails(out: OutputInfo) {
    val colors = MaterialTheme.colorScheme
    DetailRow("Device", out.deviceName.ifBlank { "—" })
    DetailRow("Route type", if (out.portType.isBlank()) "—" else "${out.kind} (${out.portType})")
    DetailRow(
        "External output",
        if (out.external) "Connected" else "Not connected",
        valueColor = if (out.external) colors.primary else colors.onSurface,
    )
    DetailRow("Output sample rate", formatRate(out.sampleRate))
    DetailRow("Output channels", if (out.channels > 0) "${out.channels}" else "—")
    DetailRow("Output latency", "${fmt1(out.latencyMs)} ms")
    if (out.ioBufferMs > 0.0) DetailRow("IO buffer", "${fmt1(out.ioBufferMs)} ms")
    DetailRow("System volume", "${(out.volume * 100).toInt()}%")
}

@Composable
private fun InfoTab(
    bridge: PlatformBridge,
    out: OutputInfo,
    song: Song,
    onSongUpdated: (Song) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val report by produceState<AudioReport?>(null, song.path) {
        value = null
        value = bridge.analyze(song)
    }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InfoCard("Live output") {
            Column {
                OutputDetails(out)
                Text(
                    "Values are what iOS reports for the audio session, not the DAC's internal rate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        InfoCard("Audio quality") {
            val r = report
            if (r == null) {
                Text("Analysing…", color = colors.onSurfaceVariant)
            } else {
                Column {
                    InfoRow("Format", r.container + if (r.lossless) " (lossless)" else " (lossy)")
                    InfoRow("Sample rate", formatRate(r.sampleRate))
                    InfoRow("Bit depth", if (r.bitDepth > 0) "${r.bitDepth}-bit" else "—")
                    InfoRow("Channels", r.channels.toString())
                    InfoRow("Bitrate", "${r.bitrateKbps} kbps")
                    InfoRow("Size", formatBytes(r.sizeBytes))
                    InfoRow("Hi-res", if (r.hiRes) "Yes" else "No")
                    r.cutoffKHz?.let { InfoRow("Spectrum cutoff", "~${fmt1(it)} kHz") }
                    r.effectiveBits?.let { InfoRow("Real bits used", "$it") }
                    Spacer(Modifier.height(8.dp))
                    Text(r.verdict, fontWeight = FontWeight.Bold)
                    Text(
                        "Estimate from the audio spectrum; not a lab test.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        InfoCard("Metadata") {
            Column {
                InfoRow("Title", song.title)
                InfoRow("Artist", song.artist.ifBlank { "—" })
                InfoRow("Album", song.album.ifBlank { "—" })
                InfoRow("File", song.path.substringAfterLast('/'))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "Searching…"
                        val s = bridge.fetchMetadata(song)
                        if (s != null) {
                            onSongUpdated(s)
                            status = "Updated: ${s.title} - ${s.artist}"
                        } else {
                            status = "No extension found metadata"
                        }
                    }
                }) { Text("Fetch with extensions") }
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun gainLabel(g: Float) = (if (g > 0.05f) "+" else "") + fmt1(g)
private fun freqLabel(f: Float) = if (f >= 1000f) "${fmt1(f / 1000f)}k" else "${f.roundToInt()}"

@Composable
private fun EqTab(eq: EqState, onEq: (EqState) -> Unit) {
    var open by remember { mutableStateOf(-1) }
    fun setBand(i: Int, b: EqBand) = onEq(eq.copy(bands = eq.bands.toMutableList().also { it[i] = b }))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Parametric equalizer", style = MaterialTheme.typography.titleMedium)
            Switch(checked = eq.enabled, onCheckedChange = { onEq(eq.copy(enabled = it)) })
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EQ_PRESETS.forEach { (name, gains) ->
                FilterChip(
                    selected = false,
                    onClick = {
                        onEq(eq.copy(enabled = true, bands = eq.bands.mapIndexed { i, b -> b.copy(gain = gains[i]) }))
                    },
                    label = { Text(name) },
                )
            }
        }
        Text("Preamp ${gainLabel(eq.preamp)} dB", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = eq.preamp, onValueChange = { onEq(eq.copy(preamp = it)) }, valueRange = -12f..12f)
        eq.bands.forEachIndexed { i, b ->
            Row(
                Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(freqLabel(b.freq), Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = b.gain.coerceIn(-12f, 12f),
                    onValueChange = { setBand(i, b.copy(gain = it)) },
                    modifier = Modifier.weight(1f),
                    valueRange = -12f..12f,
                )
                Text(gainLabel(b.gain), Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
            }
            AnimatedVisibility(open == i) {
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Frequency ${freqLabel(b.freq)} Hz", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = (ln(b.freq / 20f) / ln(1000f)).coerceIn(0f, 1f),
                        onValueChange = { setBand(i, b.copy(freq = 20f * 1000f.pow(it))) },
                    )
                    Text("Width ${fmt1(b.bandwidth)} octaves", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = b.bandwidth.coerceIn(0.1f, 3f),
                        onValueChange = { setBand(i, b.copy(bandwidth = it)) },
                        valueRange = 0.1f..3f,
                    )
                }
            }
        }
        FilledTonalButton(
            onClick = { onEq(EqState()) },
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("Reset equalizer") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "Tap a band to edit its frequency and width. Lower the preamp if boosting causes distortion.",
            Modifier.padding(bottom = 24.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
