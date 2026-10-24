package app.localplayer

import flac.*
import kotlinx.cinterop.*

private val flacWrite: FLAC__StreamDecoderWriteCallback = staticCFunction { _, frame, buffer, client ->
    val self = client!!.asStableRef<LibFlac>().get()
    self.onFrame(frame!!, buffer!!)
    FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE
}

private val flacError: FLAC__StreamDecoderErrorCallback = staticCFunction { _, _, client ->
    val self = client?.asStableRef<LibFlac>()?.get()
    if (self != null) self.errors += 1
}

/** Thin wrapper around libFLAC's stream decoder. Not thread safe: call it from one queue only. */
internal class LibFlac {
    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var errors = 0

    private var decoder: CPointer<FLAC__StreamDecoder>? = null
    private var ref: StableRef<LibFlac>? = null
    private val queue = ArrayDeque<Array<FloatArray>>()
    private var head = 0

    fun open(path: String): Boolean {
        val d = FLAC__stream_decoder_new() ?: return false
        val r = StableRef.create(this)
        val status = FLAC__stream_decoder_init_file(d, path, flacWrite, null, flacError, r.asCPointer())
        if (status != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
            FLAC__stream_decoder_delete(d)
            r.dispose()
            return false
        }
        decoder = d
        ref = r
        if (FLAC__stream_decoder_process_until_end_of_metadata(d) == 0 ||
            FLAC__stream_decoder_process_single(d) == 0
        ) {
            close()
            return false
        }
        if (channels <= 0 || sampleRate <= 0) {
            close()
            return false
        }
        return true
    }

    /** Called by libFLAC for every decoded frame. Converts samples to planar floats. */
    fun onFrame(frame: CPointer<FLAC__Frame>, buf: CPointer<CPointerVar<FLAC__int32Var>>) {
        val h = frame.pointed.header
        val n = h.blocksize.toInt()
        val ch = h.channels.toInt()
        val bps = h.bits_per_sample.toInt()
        sampleRate = h.sample_rate.toInt()
        channels = ch
        bitsPerSample = bps
        val scale = 1f / (1L shl (bps - 1)).toFloat()
        val chunk = Array(ch) { c ->
            val src = buf[c]!!
            FloatArray(n) { i -> src[i] * scale }
        }
        queue.addLast(chunk)
    }

    fun seek(frame: Long): Boolean {
        val d = decoder ?: return false
        queue.clear()
        head = 0
        if (FLAC__stream_decoder_seek_absolute(d, frame.coerceAtLeast(0L).toULong()) != 0) return true
        FLAC__stream_decoder_reset(d)
        FLAC__stream_decoder_process_until_end_of_metadata(d)
        return false
    }

    /** Fills up to [frames] frames into planar float channels. Returns how many were written (0 at the end). */
    fun read(dest: CPointer<CPointerVar<FloatVar>>, frames: Int): Int {
        val d = decoder ?: return 0
        var written = 0
        var idle = 0
        while (written < frames) {
            if (queue.isEmpty()) {
                val st = FLAC__stream_decoder_get_state(d)
                if (st == FLAC__STREAM_DECODER_END_OF_STREAM || st == FLAC__STREAM_DECODER_ABORTED) break
                if (FLAC__stream_decoder_process_single(d) == 0) break
                idle += 1
                if (idle > 2000) break
                continue
            }
            idle = 0
            val chunk = queue.first()
            val avail = chunk[0].size - head
            val take = minOf(avail, frames - written)
            for (c in 0 until minOf(channels, chunk.size)) {
                val out = dest[c] ?: continue
                val src = chunk[c]
                for (i in 0 until take) out[written + i] = src[head + i]
            }
            written += take
            head += take
            if (head >= chunk[0].size) {
                queue.removeFirst()
                head = 0
            }
        }
        return written
    }

    fun close() {
        decoder?.let {
            FLAC__stream_decoder_finish(it)
            FLAC__stream_decoder_delete(it)
        }
        decoder = null
        ref?.dispose()
        ref = null
        queue.clear()
        head = 0
    }
}
