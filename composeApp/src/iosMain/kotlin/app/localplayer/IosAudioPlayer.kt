package app.localplayer

import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFAudio.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.math.sqrt

private const val FEED_FRAMES = 16384

@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer : AudioPlayer {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override var onListened: ((Song, Long) -> Unit)? = null

    private val engine = AVAudioEngine()
    private val node = AVAudioPlayerNode()
    private val eq = AVAudioUnitEQ(numberOfBands = 10u)
    private var file: AVAudioFile? = null
    private var queue: List<Song> = emptyList()
    private var order: List<Int> = emptyList()
    private var pos = -1
    private var startFrame = 0L
    private var durationMs = 0L
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var automix = false
    private var fadeSec = 4
    private var haptics = false
    private var listenedMs = 0L
    private var ending = false
    private var avg = 0f
    private var lastBeat = 0.0
    private var feedback: UIImpactFeedbackGenerator? = null
    private var timer: NSTimer? = null
    private var rateCheck = 0
    private var rateWanted = 0.0
    private var flac: LibFlac? = null
    private var pcmFormat: AVAudioFormat? = null
    private var currentRate = 44100.0
    private var totalFrames = 0L
    private var feedGen = 0
    private val feedQueue = dispatch_queue_create("localplayer.flac", null)

    init {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        engine.attachNode(node)
        engine.attachNode(eq)
        engine.mainMixerNode.installTapOnBus(0u, 1024u, null) { buf, _ -> onTap(buf) }
        setupRemoteCommands()
        observe()
        refreshRoute()
        timer = NSTimer.scheduledTimerWithTimeInterval(0.1, true) { _ -> tick() }
    }

    // ---------- observers ----------
    private fun observe() {
        val nc = NSNotificationCenter.defaultCenter
        nc.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { n ->
            refreshRoute()
            val reason = (n?.userInfo?.get("AVAudioSessionRouteChangeReasonKey") as? Number)?.toInt()
            if (reason == 2) pausePlayback() // headphones unplugged
        }
        nc.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { n ->
            val type = (n?.userInfo?.get("AVAudioSessionInterruptionTypeKey") as? Number)?.toInt()
            val opt = (n?.userInfo?.get("AVAudioSessionInterruptionOptionKey") as? Number)?.toInt() ?: 0
            if (type == 1) pausePlayback() else if (type == 0 && (opt and 1) != 0) resume()
        }
        nc.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, NSOperationQueue.mainQueue) { _ ->
            if (file != null || flac != null) {
                val was = _state.value.isPlaying
                startPlayback(_state.value.positionMs)
                if (!was) node.pause()
            }
        }
    }

    private fun refreshRoute() {
        // Output details are read by the platform bridge; nothing to publish here.
    }

    // ---------- lock screen ----------
    private fun setupRemoteCommands() {
        val c = MPRemoteCommandCenter.sharedCommandCenter()
        c.playCommand.enabled = true
        c.playCommand.addTargetWithHandler { _ -> resume(); MPRemoteCommandHandlerStatusSuccess }
        c.pauseCommand.enabled = true
        c.pauseCommand.addTargetWithHandler { _ -> pausePlayback(); MPRemoteCommandHandlerStatusSuccess }
        c.togglePlayPauseCommand.enabled = true
        c.togglePlayPauseCommand.addTargetWithHandler { _ -> togglePlayPause(); MPRemoteCommandHandlerStatusSuccess }
        c.nextTrackCommand.enabled = true
        c.nextTrackCommand.addTargetWithHandler { _ -> next(); MPRemoteCommandHandlerStatusSuccess }
        c.previousTrackCommand.enabled = true
        c.previousTrackCommand.addTargetWithHandler { _ -> previous(); MPRemoteCommandHandlerStatusSuccess }
        c.changePlaybackPositionCommand.enabled = true
        c.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val e = event as? MPChangePlaybackPositionCommandEvent
            if (e != null) seekTo((e.positionTime * 1000).toLong())
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlaying() {
        val s = _state.value
        val song = s.song ?: return
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = mapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to song.title,
            MPMediaItemPropertyArtist to song.artist,
            MPMediaItemPropertyAlbumTitle to song.album,
            MPMediaItemPropertyPlaybackDuration to durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to s.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to (if (s.isPlaying) 1.0 else 0.0),
        )
    }

    // ---------- music haptics ----------
    private fun onTap(buf: AVAudioPCMBuffer?) {
        if (!haptics || buf == null) return
        val d = buf.floatChannelData?.get(0) ?: return
        val n = buf.frameLength.toInt()
        if (n == 0) return
        var s = 0f
        for (i in 0 until n) { val v = d[i]; s += v * v }
        val rms = sqrt(s / n)
        val now = NSDate().timeIntervalSince1970
        if (rms > 0.04f && rms > avg * 1.6f && now - lastBeat > 0.14) {
            lastBeat = now
            dispatch_async(dispatch_get_main_queue()) { buzz(rms) }
        }
        avg = avg * 0.92f + rms * 0.08f
    }

    private fun buzz(rms: Float) {
        val g = feedback ?: UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).also { feedback = it }
        g.impactOccurredWithIntensity(rms.coerceIn(0.3f, 1f).toDouble())
        g.prepare()
    }

    // ---------- queue ----------
    private fun rebuildOrder(keep: Int?) {
        val idx = queue.indices.toMutableList()
        if (shuffle) {
            idx.shuffle()
            if (keep != null) { idx.remove(keep); idx.add(0, keep) }
        }
        order = idx
        if (keep != null) pos = idx.indexOf(keep)
    }

    private fun refreshFlags() {
        _state.update {
            it.copy(
                shuffle = shuffle, repeat = repeat,
                hasPrevious = pos > 0,
                hasNext = pos < order.lastIndex || repeat != RepeatMode.OFF,
            )
        }
    }

    override fun play(queue: List<Song>, index: Int) {
        if (index !in queue.indices) return
        this.queue = queue
        rebuildOrder(index)
        load(order.indexOf(index))
    }

    private fun closeFlac() {
        feedGen += 1
        val fl = flac ?: return
        flac = null
        dispatch_sync(feedQueue) { fl.close() }
    }

    private fun openFlac(path: String): LibFlac? {
        var out: LibFlac? = null
        dispatch_sync(feedQueue) {
            try {
                val fl = LibFlac()
                if (fl.open(path)) {
                    out = fl
                } else {
                    fl.close()
                }
            } catch (t: Throwable) {
                AppLog.i("libFLAC open failed: ${t.message}")
            }
            Unit
        }
        return out
    }

    private fun load(p: Int) {
        finishListen()
        if (p !in order.indices) return
        pos = p
        val song = queue[order[p]]
        ending = false
        listenedMs = 0L
        try {
            node.stop()
            closeFlac()

            var fl: LibFlac? = null
            if (song.path.endsWith(".flac", true)) {
                fl = openFlac(song.path)
                if (fl == null) AppLog.i("libFLAC could not open ${song.title}; using Apple decoder")
            }

            val fmt: AVAudioFormat
            val frames: Long
            if (fl != null) {
                val info = FlacReader.read(song.path, false)
                frames = info?.totalSamples?.takeIf { it > 0 } ?: (song.durationMs * fl.sampleRate / 1000)
                fmt = AVAudioFormat(standardFormatWithSampleRate = fl.sampleRate.toDouble(), channels = fl.channels.toUInt())
                file = null
                flac = fl
            } else {
                val f = AVAudioFile(forReading = NSURL.fileURLWithPath(song.path), error = null)
                fmt = f.processingFormat
                frames = f.length
                file = f
            }
            pcmFormat = fmt
            currentRate = fmt.sampleRate
            totalFrames = frames

            val session = AVAudioSession.sharedInstance()
            rateWanted = fmt.sampleRate
            if (kotlin.math.abs(session.sampleRate - rateWanted) > 1.0) {
                // The output unit keeps its old rate while the engine runs, so stop it first,
                // ask for the file's rate, and let ensureEngine() start it again below.
                engine.stop()
                session.setPreferredSampleRate(rateWanted, null)
                session.setActive(true, null)
            }
            engine.disconnectNodeOutput(node)
            engine.disconnectNodeOutput(eq)
            engine.connect(node, to = eq, format = fmt)
            engine.connect(eq, to = engine.mainMixerNode, format = fmt)
            durationMs = (frames * 1000.0 / fmt.sampleRate).toLong()
            startPlayback(0)
            rateCheck = 8
            _state.update { it.copy(song = song, isPlaying = true, positionMs = 0L, durationMs = durationMs) }
            refreshFlags()
            refreshRoute()
            updateNowPlaying()
            AppLog.i("Playing ${song.title} (${fmt.sampleRate.toInt()} Hz, ${if (fl != null) "libFLAC" else "Apple decoder"})")
        } catch (t: Throwable) {
            AppLog.e("Can't play ${song.title}: ${t.message}")
            if (p < order.lastIndex) load(p + 1) else pausePlayback()
        }
    }

    private fun ensureEngine() {
        if (!engine.running) {
            engine.prepare()
            if (!engine.startAndReturnError(null)) AppLog.e("Audio engine failed to start")
        }
    }

    private fun scheduleOne(fl: LibFlac, gen: Int) {
        if (gen != feedGen) return
        val fmt = pcmFormat ?: return
        val buf = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = FEED_FRAMES.toUInt())
        val data = buf.floatChannelData ?: return
        val n = fl.read(data, FEED_FRAMES)
        if (n <= 0) return
        buf.frameLength = n.toUInt()
        node.scheduleBuffer(buf, completionHandler = {
            if (gen == feedGen) dispatch_async(feedQueue) { scheduleOne(fl, gen) }
        })
    }

    private fun startPlayback(fromMs: Long) {
        val f = file
        val fl = flac
        if (f == null && fl == null) return
        startFrame = (fromMs / 1000.0 * currentRate).toLong().coerceIn(0L, (totalFrames - 1).coerceAtLeast(0L))
        ensureEngine()
        feedGen += 1
        node.stop()
        if (fl != null) {
            val gen = feedGen
            dispatch_sync(feedQueue) {
                fl.seek(startFrame)
                repeat(4) { scheduleOne(fl, gen) }
            }
        } else if (f != null) {
            node.scheduleSegment(f, startingFrame = startFrame, frameCount = (totalFrames - startFrame).toUInt(), atTime = null, completionHandler = null)
        }
        node.play()
    }

    private fun currentPosMs(): Long {
        if (file == null && flac == null) return 0L
        val nt = node.lastRenderTime ?: return _state.value.positionMs
        val pt = node.playerTimeForNodeTime(nt) ?: return _state.value.positionMs
        return ((startFrame + pt.sampleTime) * 1000.0 / currentRate).toLong().coerceIn(0L, durationMs)
    }

    private fun tick() {
        val s = _state.value
        if (s.song == null || !s.isPlaying) return
        if (rateCheck > 0) {
            rateCheck -= 1
            if (rateCheck == 0) {
                val ss = AVAudioSession.sharedInstance()
                AppLog.i("Output rate: file ${rateWanted.toInt()} Hz, iOS gives ${ss.sampleRate.toInt()} Hz")
            }
        }
        val p = currentPosMs()
        listenedMs += 100
        _state.update { it.copy(positionMs = p, durationMs = durationMs) }
        if (automix && fadeSec > 0) {
            val fade = fadeSec * 1000f
            engine.mainMixerNode.outputVolume = minOf(1f, (p + 300) / fade, (durationMs - p) / fade).coerceIn(0f, 1f)
        }
        if (!ending && durationMs > 0 && p >= durationMs - 60) { ending = true; onTrackEnded() }
    }

    private fun finishListen() {
        val s = _state.value.song ?: return
        if (listenedMs >= 30_000 || (durationMs > 0 && listenedMs >= durationMs / 2)) onListened?.invoke(s, listenedMs)
        listenedMs = 0L
    }

    private fun onTrackEnded() {
        finishListen()
        when {
            repeat == RepeatMode.ONE -> load(pos)
            pos < order.lastIndex -> load(pos + 1)
            repeat == RepeatMode.ALL -> { if (shuffle) rebuildOrder(null); load(0) }
            else -> {
                startPlayback(0); node.pause()
                _state.update { it.copy(isPlaying = false, positionMs = 0L) }
                updateNowPlaying()
            }
        }
    }

    // ---------- controls ----------
    private fun resume() {
        if (_state.value.song == null) return
        ensureEngine()
        node.play()
        _state.update { it.copy(isPlaying = true) }
        updateNowPlaying()
    }

    private fun pausePlayback() {
        if (_state.value.song == null) return
        node.pause()
        _state.update { it.copy(isPlaying = false) }
        updateNowPlaying()
    }

    override fun playShuffled(queue: List<Song>) {
        if (queue.isEmpty()) return
        shuffle = true
        play(queue, queue.indices.random())
    }

    override fun togglePlayPause() { if (_state.value.isPlaying) pausePlayback() else resume() }

    override fun next() {
        if (pos < order.lastIndex) load(pos + 1) else if (repeat == RepeatMode.ALL && order.isNotEmpty()) load(0)
    }

        override fun previous() {
        if (_state.value.positionMs > 3000 || pos <= 0) seekTo(0) else load(pos - 1)
    }

    override fun seekTo(positionMs: Long) {
        if (file == null && flac == null) return
        val was = _state.value.isPlaying
        startPlayback(positionMs)
        if (!was) node.pause()
        _state.update { it.copy(positionMs = positionMs) }
        updateNowPlaying()
    }

    override fun toggleShuffle() {
        shuffle = !shuffle
        rebuildOrder(order.getOrNull(pos))
        refreshFlags()
    }

    override fun cycleRepeat() {
        repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }
        refreshFlags()
    }

    override fun setEq(eq: EqState) {
        this.eq.bypass = !eq.enabled
        this.eq.globalGain = eq.preamp
        eq.bands.forEachIndexed { i, b ->
            val band = this.eq.bands.getOrNull(i) as? AVAudioUnitEQFilterParameters ?: return@forEachIndexed
            band.filterType = AVAudioUnitEQFilterTypeParametric
            band.frequency = b.freq
            band.bandwidth = b.bandwidth
            band.gain = b.gain
            band.bypass = false
        }
    }

    override fun setAutomix(enabled: Boolean, fadeSec: Int) {
        automix = enabled
        this.fadeSec = fadeSec
        if (!enabled) engine.mainMixerNode.outputVolume = 1f
    }

    override fun setHaptics(enabled: Boolean) { haptics = enabled }
}
