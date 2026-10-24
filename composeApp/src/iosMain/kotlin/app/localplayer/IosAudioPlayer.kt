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
import kotlin.math.sqrt

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
            if (file != null) {
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

    private fun load(p: Int) {
        finishListen()
        if (p !in order.indices) return
        pos = p
        val song = queue[order[p]]
        ending = false
        listenedMs = 0L
        try {
            val f = AVAudioFile(forReading = NSURL.fileURLWithPath(song.path), error = null)
            val fmt = f.processingFormat
            AVAudioSession.sharedInstance().setPreferredSampleRate(fmt.sampleRate, null)
            node.stop()
            engine.disconnectNodeOutput(node)
            engine.disconnectNodeOutput(eq)
            engine.connect(node, to = eq, format = fmt)
            engine.connect(eq, to = engine.mainMixerNode, format = fmt)
            file = f
            durationMs = (f.length * 1000.0 / fmt.sampleRate).toLong()
            startPlayback(0)
            _state.update { it.copy(song = song, isPlaying = true, positionMs = 0L, durationMs = durationMs) }
            refreshFlags()
            refreshRoute()
            updateNowPlaying()
            AppLog.i("Playing ${song.title} (${fmt.sampleRate.toInt()} Hz)")
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

    private fun startPlayback(fromMs: Long) {
        val f = file ?: return
        val sr = f.processingFormat.sampleRate
        val total = f.length
        startFrame = (fromMs / 1000.0 * sr).toLong().coerceIn(0L, (total - 1).coerceAtLeast(0L))
        ensureEngine()
        node.stop()
        node.scheduleSegment(f, startingFrame = startFrame, frameCount = (total - startFrame).toUInt(), atTime = null, completionHandler = null)
        node.play()
    }

    private fun currentPosMs(): Long {
        val f = file ?: return 0L
        val nt = node.lastRenderTime ?: return _state.value.positionMs
        val pt = node.playerTimeForNodeTime(nt) ?: return _state.value.positionMs
        val sr = f.processingFormat.sampleRate
        return ((startFrame + pt.sampleTime) * 1000.0 / sr).toLong().coerceIn(0L, durationMs)
    }

    private fun tick() {
        val s = _state.value
        if (s.song == null || !s.isPlaying) return
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
        if (_state.value.positionMs > 3000 || pos 
