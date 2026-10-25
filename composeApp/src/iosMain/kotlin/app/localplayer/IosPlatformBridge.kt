package app.localplayer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.coroutines.resume
import kotlinx.cinterop.pointed
import kotlinx.cinterop.get
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf")

private fun isAudio(path: String) = path.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

private class PickerDelegate(
    private val onResult: (List<NSURL>?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onResult(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}

/** Key-value storage for stats, EQ and metadata overrides. Backed by NSUserDefaults. */
class IosStore : Store {
    private val d = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = d.stringForKey("lp.$key")
    override fun put(key: String, value: String) {
        d.setObject(value, forKey = "lp.$key")
    }
    override fun all(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in d.dictionaryRepresentation()) {
            if (k is String && k.startsWith("lp.") && v is String) out[k.removePrefix("lp.")] = v
        }
        return out
    }
}

class IosPlatformBridge : PlatformBridge {

    private class Src(val id: String, val isFolder: Boolean, val name: String, val url: NSURL?)

    private val fm = NSFileManager.defaultManager
    private val supportDir: String = makeSupportDir()
    private val srcs = mutableListOf<Src>()
    private var lastLibrary: Map<String, Song> = emptyMap()
    private var pickerDelegate: PickerDelegate? = null

    override val store: Store = IosStore()
    private val docs: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?: NSTemporaryDirectory()

    private val _output = MutableStateFlow(readOutput())
    override val output: StateFlow<OutputInfo> = _output.asStateFlow()

    init {
        AppLog.clock = { NSDateFormatter().apply { dateFormat = "HH:mm:ss" }.stringFromDate(NSDate()) }
        AppLog.sink = { writeAbs("$docs/localplayer.log", AppLog.lines.value.joinToString("\n")) }
        AppLog.i("App started")
        loadSources()
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionRouteChangeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ -> refreshOutput() }
    }

    // ---------- storage ----------

    private fun makeSupportDir(): String {
        val base = fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)?.path
            ?: NSTemporaryDirectory()
        val dir = "$base/LocalPlayer"
        fm.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    override fun readText(name: String): String? =
        NSData.dataWithContentsOfFile("$supportDir/$name")?.toByteArray()?.decodeToString()

    override fun writeText(name: String, text: String) {
        text.encodeToByteArray().toNSData().writeToFile("$supportDir/$name", true)
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun validSourcePath(url: NSURL?): String? {
        val path = url?.path ?: return null
        if (!fm.fileExistsAtPath(path)) return null
        return path
    }

    private fun isFolderPath(path: String): Boolean {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return false
        val type = attrs[NSFileType] as? String ?: return false
        return type == NSFileTypeDirectory
    }

    // ---------- audio output ----------

    private fun classify(type: String): Pair<String, Boolean> = when (type) {
        AVAudioSessionPortBuiltInSpeaker -> "Speaker" to false
        AVAudioSessionPortBuiltInReceiver -> "Receiver" to false
        AVAudioSessionPortHeadphones -> "Headphones / wired" to true
        AVAudioSessionPortUSBAudio -> "USB audio" to true
        AVAudioSessionPortBluetoothA2DP,
        AVAudioSessionPortBluetoothLE,
        AVAudioSessionPortBluetoothHFP -> "Bluetooth" to true
        AVAudioSessionPortAirPlay -> "AirPlay" to true
        AVAudioSessionPortHDMI -> "HDMI" to true
        AVAudioSessionPortLineOut -> "Line out" to true
        else -> (if (type.isBlank()) "Unknown" else "Other") to type.isNotBlank()
    }

    private fun readOutput(): OutputInfo {
        return try {
            val s = AVAudioSession.sharedInstance()
            val port = s.currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
            val type = port?.portType ?: ""
            val (kind, external) = classify(type)
            OutputInfo(
                deviceName = port?.portName ?: "",
                portType = type,
                kind = kind,
                external = external,
                sampleRate = s.sampleRate.toInt(),
                channels = s.outputNumberOfChannels.toInt(),
                latencyMs = s.outputLatency * 1000.0,
                ioBufferMs = s.IOBufferDuration * 1000.0,
                volume = s.outputVolume,
            )
        } catch (_: Throwable) {
            OutputInfo()
        }
    }

    override fun refreshOutput() {
        _output.value = readOutput()
    }

    // ---------- sources (folders and files the user picked) ----------

    private fun loadSources() {
        val text = readText("sources.tsv") ?: return
        val loaded = mutableListOf<Src>()
        for (line in text.lines()) {
            val f = line.split('\t')
            if (f.size < 3) continue
            val bookmark = NSData.dataWithContentsOfFile("$supportDir/bm_${f[0]}.bin") ?: continue
            val url = NSURL.URLByResolvingBookmarkData(bookmark, 0uL, null, null, null) ?: continue
            // Access has to stay open while the app reads files below this URL.
            url.startAccessingSecurityScopedResource()
            val path = validSourcePath(url)
            if (path == null) {
                // Offline for now (for example an unplugged drive). Keep it; scan() skips it.
                AppLog.i("Source not reachable right now: ${f[2]}")
            } else if (f[1] == "folder" && !isFolderPath(path)) {
                AppLog.i("Dropping source saved as folder but not a folder: ${f[2]}")
                url.stopAccessingSecurityScopedResource()
                continue
            }
            loaded += Src(f[0], f[1] == "folder", f[2], url)
        }
        srcs.clear()
        srcs.addAll(loaded)
        if (loaded.size != text.lines().count { it.isNotBlank() }) {
            saveSources()
        }
    }

    private fun saveSources() {
        writeText(
            "sources.tsv",
            srcs.joinToString("\n") { "${it.id}\t${if (it.isFolder) "folder" else "file"}\t${clean(it.name)}" },
        )
    }

    private fun addSources(urls: List<NSURL>, folder: Boolean): Boolean {
        var added = false
        for (url in urls) {
            // Picked URLs are security scoped: open access first, then look at them.
            val opened = url.startAccessingSecurityScopedResource()
            var keep = false
            try {
                val path = validSourcePath(url)
                if (path == null) {
                    AppLog.i("Skipping unavailable picked source: ${url.path ?: "<unknown>"}")
                    continue
                }
                if (srcs.any { it.url?.path == path }) continue
                val data = url.bookmarkDataWithOptions(0uL, null, null, null)
                if (data == null) {
                    AppLog.e("Could not bookmark source: $path")
                    continue
                }
                val id = NSUUID().UUIDString
                data.writeToFile("$supportDir/bm_$id.bin", true)
                srcs += Src(id, folder, url.lastPathComponent ?: path, url)
                added = true
                keep = true // access stays open for the life of the source
            } catch (t: Throwable) {
                AppLog.e("Failed to add source: ${t.message ?: "unknown error"}")
            } finally {
                if (opened && !keep) url.stopAccessingSecurityScopedResource()
            }
        }
        if (added) saveSources()
        return added
    }

    override fun sources(): List<SourceInfo> = srcs.map { SourceInfo(it.id, it.name, it.isFolder) }

    override fun removeSource(id: String) {
        val src = srcs.firstOrNull { it.id == id } ?: return
        src.url?.stopAccessingSecurityScopedResource()
        srcs.remove(src)
        fm.removeItemAtPath("$supportDir/bm_$id.bin", null)
        saveSources()
    }

    // ---------- pickers ----------

    private fun topViewController(): UIViewController? {
        var vc = (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)?.rootViewController
        while (true) {
            val next = vc?.presentedViewController ?: break
            vc = next
        }
        return vc
    }

    private suspend fun presentPicker(types: List<*>, multiple: Boolean): List<NSURL>? =
        suspendCancellableCoroutine { cont ->
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = types)
            picker.allowsMultipleSelection = multiple
            val delegate = PickerDelegate { urls ->
                pickerDelegate = null
                if (cont.isActive) cont.resume(urls)
            }
            pickerDelegate = delegate
            picker.delegate = delegate
            val root = topViewController()
            if (root == null) {
                pickerDelegate = null
                cont.resume(null)
            } else {
                root.presentViewController(picker, true, null)
            }
        }

    override suspend fun pickFolder(): Boolean {
        val urls = presentPicker(listOf(UTTypeFolder), false) ?: return false
        return addSources(urls, true)
    }

    override suspend fun pickSongs(): Boolean {
        val urls = presentPicker(listOf(UTTypeAudio), true) ?: return false
        return addSources(urls, false)
    }

    // ---------- library ----------

    override fun loadCachedLibrary(): List<Song> {
        val text = readText("library.tsv") ?: return emptyList()
        val list = text.lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 11) {
                null
            } else {
                try {
                    Song(
                        path = f[0], title = f[1], artist = f[2], album = f[3],
                        durationMs = f[4].toLong(), sizeBytes = f[5].toLong(), format = f[6],
                        sampleRate = f[7].toInt(), channels = f[8].toInt(),
                        bitDepth = f[9].toInt(), bitrateKbps = f[10].toInt(),
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }.filter { fm.fileExistsAtPath(it.path) }
        lastLibrary = list.associateBy { it.path }
        return list.map { withOverride(it) }
    }

    private fun saveLibrary(list: List<Song>) {
        val sb = StringBuilder()
        for (s in list) {
            sb.append(
                listOf(
                    s.path, s.title, s.artist, s.album, s.durationMs, s.sizeBytes, s.format,
                    s.sampleRate, s.channels, s.bitDepth, s.bitrateKbps,
                ).joinToString("\t") { clean(it.toString()) },
            ).append('\n')
        }
        writeText("library.tsv", sb.toString())
    }

    private fun collectFiles(root: String, out: MutableCollection<String>) {
        val subs = fm.subpathsOfDirectoryAtPath(root, null) ?: return
        for (rel in subs) {
            val r = rel as? String ?: continue
            if (r.startsWith(".") || r.contains("/.")) continue
            if (isAudio(r)) out.add("$root/$r")
        }
    }

    private fun fileSize(path: String): Long {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return 0L
        val v = attrs[NSFileSize]
        return (v as? NSNumber)?.longLongValue ?: (v as? Number)?.toLong() ?: 0L
    }

    override suspend fun scan(): List<Song> = withContext(Dispatchers.Default) {
        val paths = LinkedHashSet<String>()
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        if (docs != null) collectFiles(docs, paths)
        for (src in srcs.toList()) {
            val p = src.url?.path ?: continue
            if (!fm.fileExistsAtPath(p)) {
                AppLog.i("Skipping unavailable source during scan: ${src.name}")
                continue
            }
            if (src.isFolder) collectFiles(p, paths) else if (isAudio(p)) paths.add(p)
        }
        val cache = lastLibrary
        val result = ArrayList<Song>()
        for (path in paths) {
            val size = fileSize(path)
            val cached = cache[path]
            result += if (cached != null && size > 0 && cached.sizeBytes == size) cached else readSong(path, size)
        }
        result.sortBy { it.title.lowercase() }
        lastLibrary = result.associateBy { it.path }
        saveLibrary(result)
        AppLog.i("Library: ${result.size} songs")
        result.map { withOverride(it) }.sortedBy { it.title.lowercase() }
    }

    private fun readSong(path: String, size: Long): Song {
        val ext = path.substringAfterLast('.', "").lowercase()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = ext.uppercase()
        val url = NSURL.fileURLWithPath(path)

        if (ext == "flac") {
            val f = FlacReader.read(path, false)
            if (f != null) {
                title = f.title
                artist = f.artist
                album = f.album
                sampleRate = f.sampleRate
                channels = f.channels
                bits = f.bitDepth
                if (f.sampleRate > 0 && f.totalSamples > 0) durationMs = f.totalSamples * 1000L / f.sampleRate
            }
        }

        try {
            val asset = AVURLAsset(uRL = url, options = null)
            for (raw in asset.commonMetadata) {
                val item = raw as? AVMetadataItem ?: continue
                when (item.commonKey) {
                    AVMetadataCommonKeyTitle -> if (title.isNullOrBlank()) title = item.stringValue
                    AVMetadataCommonKeyArtist -> if (artist.isNullOrBlank()) artist = item.stringValue
                    AVMetadataCommonKeyAlbumName -> if (album.isNullOrBlank()) album = item.stringValue
                }
            }
            if (durationMs == 0L) {
                val seconds = CMTimeGetSeconds(asset.duration)
                if (!seconds.isNaN() && seconds > 0) durationMs = (seconds * 1000).toLong()
            }
        } catch (_: Throwable) {
        }

        if (sampleRate == 0 || channels == 0 || bits == 0) {
            try {
                val af = AVAudioFile(forReading = url, error = null)
                val fmt = af.fileFormat
                if (sampleRate == 0) sampleRate = fmt.sampleRate.toInt()
                if (channels == 0) channels = fmt.channelCount.toInt()
                val asbd = fmt.streamDescription?.pointed
                if (asbd != null) {
                    val alac = asbd.mFormatID == 0x616C6163u
                    if (ext == "m4a" || ext == "mp4") format = if (alac) "ALAC" else "AAC"
                    if (bits == 0) {
                        bits = if (alac) {
                            when (asbd.mFormatFlags) {
                                1u -> 16
                                2u -> 20
                                3u -> 24
                                4u -> 32
                                else -> 0
                            }
                        } else {
                            asbd.mBitsPerChannel.toInt()
                        }
                    }
                }
                if (durationMs == 0L && sampleRate > 0 && af.length > 0) durationMs = af.length * 1000L / sampleRate
            } catch (_: Throwable) {
            }
        }

        val bitrate = if (durationMs > 0 && size > 0) ((size * 8L) / durationMs).toInt() else 0
        val fallbackTitle = path.substringAfterLast('/').substringBeforeLast('.')
        return Song(
            path = path,
            title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            artist = artist?.takeIf { it.isNotBlank() } ?: "",
            album = album?.takeIf { it.isNotBlank() } ?: "",
            durationMs = durationMs,
            sizeBytes = size,
            format = format,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bits,
            bitrateKbps = bitrate,
        )
    }

    // ---------- artwork ----------

    private fun avArtwork(path: String): ByteArray? {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        for (raw in asset.commonMetadata) {
            val item = raw as? AVMetadataItem ?: continue
            if (item.commonKey == AVMetadataCommonKeyArtwork) {
                val data = item.dataValue ?: (item.value as? NSData)
                if (data != null) return data.toByteArray()
            }
        }
        return null
    }

    override fun artwork(path: String): ImageBitmap? {
        return try {
            val flacPic = if (path.substringAfterLast('.', "").lowercase() == "flac") {
                FlacReader.read(path, true)?.picture
            } else {
                null
            }
            val bytes = flacPic ?: avArtwork(path) ?: return null
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- helpers for the extra features ----------

    private fun writeAbs(path: String, text: String) {
        fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        text.encodeToByteArray().toNSData().writeToFile(path, true)
    }

    private fun readAbs(path: String): String? =
        NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()

    private fun withOverride(s: Song): Song {
        val v = store.get("meta:${s.key()}")?.split('\u0001') ?: return s
        return if (v.size >= 3) s.copy(title = v[0].ifBlank { s.title }, artist = v[1], album = v[2]) else s
    }

    private fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if ((c in 48..57) || (c in 65..90) || (c in 97..122) || c == 45 || c == 46 || c == 95 || c == 126) {
                append(c.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[c shr 4])
                append("0123456789ABCDEF"[c and 15])
            }
        }
    }

    // ---------- extensions ----------

    private class Ext(val name: String, val type: String, val url: String, val map: Map<String, String>, val builtin: Boolean)

    private fun exts(): List<Ext> {
        val list = mutableListOf(
            Ext(
                "LRCLIB", "lyrics",
                "https://lrclib.net/api/get?artist_name={artist}&track_name={title}&album_name={album}&duration={duration}",
                mapOf("synced" to "syncedLyrics", "plain" to "plainLyrics"), true,
            ),
            Ext(
                "LRCLIB search", "lyrics",
                "https://lrclib.net/api/search?track_name={title}&artist_name={artist}",
                mapOf("synced" to "0.syncedLyrics", "plain" to "0.plainLyrics"), true,
            ),
            Ext(
                "iTunes Search", "metadata",
                "https://itunes.apple.com/search?term={artist}%20{title}&entity=song&limit=1",
                mapOf("title" to "results.0.trackName", "artist" to "results.0.artistName", "album" to "results.0.collectionName"), true,
            ),
        )
        val dir = "$docs/Extensions"
        val files = (fm.contentsOfDirectoryAtPath(dir, null) as? List<*>).orEmpty().filterIsInstance<String>()
        for (f in files.filter { it.endsWith(".json") }) {
            try {
                val data = NSData.dataWithContentsOfFile("$dir/$f") ?: continue
                val j = NSJSONSerialization.JSONObjectWithData(data, 0uL, null) as? Map<*, *> ?: continue
                val m = HashMap<String, String>()
                for ((k, v) in j) if (k is String && v is String) m[k] = v
                list += Ext(m["name"] ?: f, m["type"] ?: "", m["url"] ?: "", m, false)
            } catch (t: Throwable) {
                AppLog.e("Bad extension $f: ${t.message}")
            }
        }
        return list
    }

    private fun fill(url: String, s: Song) = url
        .replace("{artist}", enc(s.artist)).replace("{title}", enc(s.title))
        .replace("{album}", enc(s.album)).replace("{duration}", (s.durationMs / 1000).toString())

    private fun httpJson(url: String): Any? {
        val u = NSURL.URLWithString(url) ?: return null
        val data = NSData.dataWithContentsOfURL(u) ?: return null
        return NSJSONSerialization.JSONObjectWithData(data, 0uL, null)
    }

    private fun pick(rootObj: Any?, path: String): Any? {
        var cur: Any? = rootObj
        for (part in path.split('.')) {
            val c = cur
            cur = when (c) {
                is Map<*, *> -> c[part]
                is List<*> -> part.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            }
        }
        return cur
    }

    override suspend fun extensions(): List<ExtensionInfo> = withContext(Dispatchers.Default) {
        exts().map { ExtensionInfo(it.name, it.type, if (it.builtin) "Built in" else "Extensions folder") }
    }

    override suspend fun installSampleExtensions(): String = withContext(Dispatchers.Default) {
        writeAbs(
            "$docs/Extensions/sample-lyrics.json",
            """{
  "name": "My lyrics source",
  "type": "lyrics",
  "url": "https://lrclib.net/api/get?artist_name={artist}&track_name={title}&album_name={album}&duration={duration}",
  "synced": "syncedLyrics",
  "plain": "plainLyrics"
}""",
        )
        writeAbs(
            "$docs/Extensions/sample-metadata.json",
            """{
  "name": "My metadata source",
  "type": "metadata",
  "url": "https://itunes.apple.com/search?term={artist}%20{title}&entity=song&limit=1",
  "title": "results.0.trackName",
  "artist": "results.0.artistName",
  "album": "results.0.collectionName"
}""",
        )
        "Created 2 samples in Files > LocalPlayer > Extensions. Edit them there."
    }

    override suspend fun fetchMetadata(song: Song): Song? = withContext(Dispatchers.Default) {
        for (e in exts().filter { it.type == "metadata" }) {
            try {
                val j = httpJson(fill(e.url, song))
                val t = e.map["title"]?.let { pick(j, it) } as? String
                val a = e.map["artist"]?.let { pick(j, it) } as? String
                val al = e.map["album"]?.let { pick(j, it) } as? String
                if (!t.isNullOrBlank() || !a.isNullOrBlank()) {
                    val s = song.copy(title = t ?: song.title, artist = a ?: song.artist, album = al ?: song.album)
                    store.put("meta:${song.key()}", "${s.title}\u0001${s.artist}\u0001${s.album}")
                    AppLog.i("Metadata from ${e.name}: ${s.title}")
                    return@withContext s
                }
            } catch (t: Throwable) {
                AppLog.i("Metadata via ${e.name} failed")
            }
        }
        null
    }

    // ---------- lyrics ----------

    private fun toResult(text: String, source: String): LyricsResult {
        val lines = parseLrc(text)
        return if (lines.isNotEmpty()) LyricsResult(lines, true, source)
        else LyricsResult(text.lines().map { LyricLine(-1L, it) }, false, source)
    }

    override suspend fun lyrics(song: Song, online: Boolean): LyricsResult? = withContext(Dispatchers.Default) {
        readAbs(song.path.substringBeforeLast('.') + ".lrc")?.let { return@withContext toResult(it, "Local .lrc file") }
        val saved = "$supportDir/lyrics/${song.key().replace('/', '_')}.lrc"
        readAbs(saved)?.let { return@withContext toResult(it, "Saved copy") }
        if (song.path.endsWith(".flac", true)) {
            val tags = FlacReader.read(song.path, false)?.tags
            val tag = tags?.get("LYRICS") ?: tags?.get("UNSYNCEDLYRICS")
            if (!tag.isNullOrBlank()) return@withContext toResult(tag, "Embedded tag")
        }
        if (!online) return@withContext null
        for (e in exts().filter { it.type == "lyrics" }) {
            try {
                val j = httpJson(fill(e.url, song))
                val synced = e.map["synced"]?.let { pick(j, it) } as? String
                val plain = e.map["plain"]?.let { pick(j, it) } as? String
                if (!synced.isNullOrBlank()) {
                    writeAbs(saved, synced)
                    return@withContext toResult(synced, e.name)
                }
                if (!plain.isNullOrBlank()) return@withContext toResult(plain, e.name)
            } catch (t: Throwable) {
                AppLog.i("Lyrics via ${e.name} failed")
            }
        }
        null
    }

    // ---------- hi-res analysis ----------

    override suspend fun analyze(song: Song): AudioReport? = withContext(Dispatchers.Default) {
        try {
            val full = song.path
            val ext = full.substringAfterLast('.', "").lowercase()
            val af = AVAudioFile(forReading = NSURL.fileURLWithPath(full), error = null)
            val fmt = af.processingFormat
            var rate: Int
            var bits: Int
            var ch: Int
            var lossless = ext in setOf("flac", "wav", "aif", "aiff", "caf")
            val flac = if (ext == "flac") FlacReader.read(full, false) else null
            if (flac != null) {
                rate = flac.sampleRate
                bits = flac.bitDepth
                ch = flac.channels
            } else {
                val st = af.fileFormat.settings
                rate = af.fileFormat.sampleRate.toInt()
                ch = af.fileFormat.channelCount.toInt()
                bits = ((st["AVLinearPCMBitDepthKey"] ?: st["AVEncoderBitDepthHintKey"]) as? Number)?.toInt() ?: 0
                if ((st["AVFormatIDKey"] as? Number)?.toInt() == 1634492771) lossless = true // Apple Lossless
            }
            val windows = mutableListOf<FloatArray>()
            var orBits = 0
            val frames = af.length
            if (frames > 40_000) {
                val buf = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = 8192u)
                for (k in 1..8) {
                    af.framePosition = (frames - 8192) * k / 9
                    if (!af.readIntoBuffer(buf, 8192u, null)) continue
                    val d = buf.floatChannelData?.get(0) ?: continue
                    val n = buf.frameLength.toInt()
                    windows += FloatArray(n) { d[it] }
                    for (i in 0 until n) orBits = orBits or (d[i] * 8388608f).toInt()
                }
            }
            val eff = if (bits > 16 && orBits != 0) (24 - orBits.countTrailingZeroBits()).coerceAtMost(bits) else null
            val cutoff = spectrumCutoffKHz(windows, fmt.sampleRate.toInt())
            val durMs = (frames * 1000.0 / fmt.sampleRate).toLong()
            buildReport(ext, rate, bits, ch, fileSize(full), durMs, lossless, cutoff, eff)
        } catch (t: Throwable) {
            AppLog.e("Analysis failed: ${t.message}")
            null
        }
    }

    // ---------- backup / restore ----------

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(
                    when (s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    override suspend fun backup(): String = withContext(Dispatchers.Default) {
        val all = HashMap<String, String>(store.all())
        readText("settings.txt")?.let { all["__settings"] = it }
        writeAbs(
            "$docs/Backups/LocalPlayer-backup.txt",
            all.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${esc(it.value)}" },
        )
        AppLog.i("Backup saved (${all.size} items)")
        "Saved ${all.size} items to Files > LocalPlayer > Backups"
    }

    override suspend fun restore(): String = withContext(Dispatchers.Default) {
        val text = readAbs("$docs/Backups/LocalPlayer-backup.txt")
            ?: return@withContext "No backup found in Files > LocalPlayer > Backups"
        var n = 0
        for (line in text.lines()) {
            val t = line.indexOf('\t')
            if (t > 0) {
                val key = line.substring(0, t)
                val value = unesc(line.substring(t + 1))
                if (key == "__settings") writeText("settings.txt", value) else store.put(key, value)
                n++
            }
        }
        AppLog.i("Restored $n items")
        "Restored $n items. Close and reopen the app to apply."
    }
}
