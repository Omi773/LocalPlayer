package app.localplayer
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt
data class Song(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long = 0L,
    val format: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
)
/** Uses the full file path so different files with the same filename have separate keys. */
fun Song.key(): String = path
enum class RepeatMode { OFF, ALL, ONE }
data class PlaybackState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
)
/** What iOS reports for the current audio route (not the DAC's internal state). */
data class OutputInfo(
    val deviceName: String = "",
    val portType: String = "",
    val kind: String = "",
    val external: Boolean = false,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val ioBufferMs: Double = 0.0,
    val latencyMs: Double = 0.0,
    val volume: Float = 0f,
)
data class SourceInfo(val id: String, val name: String, val isFolder: Boolean)
// ---------- equalizer ----------
data class EqBand(val freq: Float, val gain: Float, val bandwidth: Float)
data class EqState(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val bands: List<EqBand> = defaultBands(),
)
val EQ_FREQS = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
fun defaultBands(): List<EqBand> = EQ_FREQS.map { EqBand(it, 0f, 1f) }
val EQ_PRESETS: Map<String, List<Float>> = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
    "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
    "Rock" to listOf(4f, 3f, 1f, -1f, -2f, 0f, 2f, 3f, 4f, 4f),
    "Acoustic" to listOf(3f, 3f, 2f, 0f, 1f, 1f, 2f, 3f, 3f, 2f),
)
fun encodeEq(e: EqState): String =
    "${if (e.enabled) 1 else 0};${e.preamp};" +
        e.bands.joinToString("|") { "${it.freq},${it.gain},${it.bandwidth}" }
fun decodeEq(s: String?): EqState = try {
    val p = s!!.split(";")
    val bands = p[2].split("|").map { b ->
        val v = b.split(",")
        EqBand(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
    }
    require(bands.size == 10)
    EqState(p[0] == "1", p[1].toFloat(), bands)
} catch (_: Throwable) {
    EqState()
}
// ---------- lyrics, analysis, extensions ----------
data class LyricWord(val timeMs: Long, val text: String)
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())
data class LyricsResult(val lines: List<LyricLine>, val synced: Boolean, val source: String)
data class AudioReport(
    val container: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val lossless: Boolean,
    val hiRes: Boolean,
    val cutoffKHz: Float?,
    val effectiveBits: Int?,
    val verdict: String,
)
data class ExtensionInfo(val name: String, val type: String, val source: String)
// ---------- storage, stats, log ----------
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun all(): Map<String, String>
}
class Stats(private val store: Store) {
    fun record(key: String, ms: Long) {
        val cur = store.get("stat:$key")?.split(";")
        val plays = (cur?.getOrNull(0)?.toIntOrNull() ?: 0) + 1
        val total = (cur?.getOrNull(1)?.toLongOrNull() ?: 0L) + ms
        store.put("stat:$key", "$plays;$total")
    }
    /** key -> (plays, total listened ms) */
    fun all(): Map<String, Pair<Int, Long>> = store.all()
        .filterKeys { it.startsWith("stat:") }
        .mapNotNull { (k, v) ->
            val p = v.split(";")
            val plays = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            k.removePrefix("stat:") to (plays to (p.getOrNull(1)?.toLongOrNull() ?: 0L))
        }.toMap()
}
object AppLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    var sink: ((String) -> Unit)? = null
    var clock: () -> String = { "" }
    fun i(msg: String) = add("I", msg)
    fun e(msg: String) {
        add("E", msg)
        _error.value = msg
    }
    fun dismissError() {
        _error.value = null
    }
    fun clear() {
        _lines.value = emptyList()
    }
    private fun add(level: String, msg: String) {
        val line = "${clock()} $level $msg"
        _lines.update { (it + line).takeLast(300) }
        sink?.invoke(line)
    }
}
// ---------- platform interfaces ----------
interface AudioPlayer {
    val state: StateFlow<PlaybackState>
    var onListened: ((Song, Long) -> Unit)?
    fun play(queue: List<Song>, index: Int)
    fun playShuffled(queue: List<Song>)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun setEq(eq: EqState)
    fun setAutomix(enabled: Boolean, fadeSec: Int)
    fun setHaptics(enabled: Boolean)
}
interface PlatformBridge {
    val output: StateFlow<OutputInfo>
    val store: Store
    fun refreshOutput()
    suspend fun pickFolder(): Boolean
    suspend fun pickSongs(): Boolean
    fun sources(): List<SourceInfo>
    fun removeSource(id: String)
    fun loadCachedLibrary(): List<Song>
    suspend fun scan(): List<Song>
    fun artwork(path: String): ImageBitmap?
    fun readText(name: String): String?
    fun writeText(name: String, text: String)
    suspend fun lyrics(song: Song, online: Boolean): LyricsResult?
    suspend fun analyze(song: Song): AudioReport?
    suspend fun fetchMetadata(song: Song): Song?
    suspend fun extensions(): List<ExtensionInfo>
    suspend fun installSampleExtensions(): String
    suspend fun backup(): String
    suspend fun restore(): String
}
// ---------- formatting ----------
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
fun formatRate(hz: Int): String {
    if (hz <= 0) return "—"
    return if (hz % 1000 == 0) "${hz / 1000} kHz"
    else "${hz / 1000}.${(hz % 1000) / 100} kHz"
}
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    return if (bytes >= 1_048_576L) {
        val tenths = bytes * 10 / 1_048_576L
        "${tenths / 10}.${tenths % 10} MB"
    } else {
        "${bytes / 1024} KB"
    }
}
fun fmt1(value: Double): String {
    val t = (value * 10).roundToInt()
    val sign = if (t < 0) "-" else ""
    val a = abs(t)
    return "$sign${a / 10}.${a % 10}"
}
fun fmt1(value: Float): String = fmt1(value.toDouble())
/** One line such as "FLAC · 96 kHz · 24-bit → 48 kHz · JM7L". */
fun qualityLine(song: Song, out: OutputInfo): String {
    val parts = mutableListOf<String>()
    if (song.format.isNotBlank()) parts += song.format
    if (song.sampleRate > 0) parts += formatRate(song.sampleRate)
    if (song.bitDepth > 0) parts += "${song.bitDepth}-bit"
    val source = parts.joinToString(" · ")
    val outPart = if (out.sampleRate > 0) "→ ${formatRate(out.sampleRate)}" else ""
    return listOf(source, outPart, out.deviceName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}