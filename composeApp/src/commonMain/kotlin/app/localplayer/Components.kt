package app.localplayer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Main-thread cache of decoded cover art. */
class ArtCache(private val bridge: PlatformBridge) {
    private val map = LinkedHashMap<String, ImageBitmap?>()

    fun peek(path: String?): ImageBitmap? = if (path == null) null else map[path]

    suspend fun get(path: String): ImageBitmap? {
        if (map.containsKey(path)) return map[path]
        val bmp = withContext(Dispatchers.Default) { bridge.artwork(path) }
        map[path] = bmp
        if (map.size > 48) {
            val first = map.keys.first()
            map.remove(first)
        }
        return bmp
    }
}

@Composable
fun Artwork(
    path: String?,
    cache: ArtCache,
    modifier: Modifier,
    shape: Shape,
    placeholder: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    var bitmap by remember(path) { mutableStateOf(cache.peek(path)) }
    LaunchedEffect(path) {
        if (path != null && bitmap == null) bitmap = cache.get(path)
    }
    Box(
        modifier.clip(shape).background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
fun WavySeekBar(
    progress: Float,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "phase",
    )
    val amp by animateFloatAsState(if (playing) 1f else 0f, label = "amp")
    var drag by remember { mutableStateOf<Float?>(null) }
    val onSeekState by rememberUpdatedState(onSeek)
    val shown = drag ?: progress

    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onSeekState((off.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> drag = (off.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { onSeekState(it) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val strokeW = 4.dp.toPx()
        val cy = size.height / 2f
        val w = size.width
        val x = w * shown.coerceIn(0f, 1f)
        val amplitude = 4.dp.toPx() * amp
        val wavelength = 26.dp.toPx()

        drawLine(trackColor, Offset(x, cy), Offset(w, cy), strokeWidth = strokeW, cap = StrokeCap.Round)

        val path = Path()
        path.moveTo(0f, cy)
        var px = 0f
        while (px <= x) {
            val y = cy + amplitude * sin((px / wavelength) * 2f * PI.toFloat() + phase)
            path.lineTo(px, y)
            px += 3f
        }
        drawPath(path, activeColor, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        drawRoundRect(
            activeColor,
            topLeft = Offset(x - 2.dp.toPx(), cy - 11.dp.toPx()),
            size = Size(4.dp.toPx(), 22.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
fun SongRow(
    song: Song,
    current: Boolean,
    playing: Boolean,
    cache: ArtCache,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val bg = if (current) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceContainerLow
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.path, cache, Modifier.size(52.dp), RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (current) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current && playing) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.primary)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Song info") },
                    onClick = {
                        menu = false
                        onInfo()
                    },
                )
            }
        }
    }
}

@Composable
fun GroupRow(
    name: String,
    subtitle: String,
    artPath: String?,
    cache: ArtCache,
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artPath, cache, Modifier.size(52.dp), shape)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer8()
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun Spacer8() {
    Box(Modifier.size(8.dp))
}

@Composable
fun FileDetailRows(song: Song) {
    DetailRow("Format", song.format.ifBlank { "—" })
    DetailRow("Sample rate", formatRate(song.sampleRate))
    DetailRow("Bit depth", if (song.bitDepth > 0) "${song.bitDepth}-bit" else "not reported")
    DetailRow("Channels", if (song.channels > 0) "${song.channels}" else "—")
    DetailRow("Bitrate", if (song.bitrateKbps > 0) "${song.bitrateKbps} kbps (average)" else "—")
    DetailRow("Duration", formatTime(song.durationMs))
    DetailRow("Size", formatBytes(song.sizeBytes))
    DetailRow("File", song.path.substringAfterLast('/'))
}
