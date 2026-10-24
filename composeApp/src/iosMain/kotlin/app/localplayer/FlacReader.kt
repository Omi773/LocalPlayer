package app.localplayer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.posix.memcpy

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    }
    return out
}

internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.create(bytes = null, length = 0uL)
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

internal class FlacInfo {
    var sampleRate = 0
    var channels = 0
    var bitDepth = 0
    var totalSamples = 0L
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var picture: ByteArray? = null
    var pictureType = -1
    val tags = HashMap<String, String>()
}

/** Minimal FLAC metadata reader: STREAMINFO, VORBIS_COMMENT and PICTURE blocks. */
internal object FlacReader {

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le32(b: ByteArray, i: Int) =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    private fun NSFileHandle.readBytes(n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val data: NSData? = readDataOfLength(n.toULong())
        return data?.toByteArray() ?: ByteArray(0)
    }

    fun read(path: String, wantPicture: Boolean): FlacInfo? {
        val fh = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        try {
            val magic = fh.readBytes(4)
            if (magic.size < 4 || magic.decodeToString() != "fLaC") return null
            val info = FlacInfo()
            var last = false
            var guard = 0
            while (!last && guard < 200) {
                guard++
                val h = fh.readBytes(4)
                if (h.size < 4) break
                last = (u8(h, 0) and 0x80) != 0
                val type = u8(h, 0) and 0x7F
                val len = (u8(h, 1) shl 16) or (u8(h, 2) shl 8) or u8(h, 3)
                val wanted = type == 0 || type == 4 || (type == 6 && wantPicture)
                if (!wanted || len > 24_000_000) {
                    fh.seekToFileOffset(fh.offsetInFile + len.toULong())
                    continue
                }
                val d = fh.readBytes(len)
                if (d.size < len) break
                when (type) {
                    0 -> parseStreamInfo(d, info)
                    4 -> parseVorbis(d, info)
                    6 -> parsePicture(d, info)
                }
            }
            return info
        } catch (_: Throwable) {
            return null
        } finally {
            fh.closeFile()
        }
    }

    private fun parseStreamInfo(d: ByteArray, info: FlacInfo) {
        if (d.size < 18) return
        info.sampleRate = (u8(d, 10) shl 12) or (u8(d, 11) shl 4) or (u8(d, 12) shr 4)
        info.channels = ((u8(d, 12) shr 1) and 0x07) + 1
        info.bitDepth = (((u8(d, 12) and 1) shl 4) or (u8(d, 13) shr 4)) + 1
        info.totalSamples = ((u8(d, 13) and 0x0F).toLong() shl 32) or
            (u8(d, 14).toLong() shl 24) or (u8(d, 15).toLong() shl 16) or
            (u8(d, 16).toLong() shl 8) or u8(d, 17).toLong()
    }

    private fun parseVorbis(d: ByteArray, info: FlacInfo) {
        try {
            if (d.size < 8) return
            var p = 0
            val vendorLen = le32(d, p)
            p += 4 + vendorLen
            if (vendorLen < 0 || p + 4 > d.size) return
            val count = le32(d, p)
            p += 4
            var i = 0
            while (i < count) {
                i++
                if (p + 4 > d.size) return
                val len = le32(d, p)
                p += 4
                if (len < 0 || p + len > d.size) return
                val text = d.decodeToString(p, p + len)
                p += len
                val eq = text.indexOf('=')
                if (eq <= 0) continue
                val key = text.substring(0, eq).uppercase()
                val value = text.substring(eq + 1).trim()
                if (value.isEmpty()) continue
                if (!info.tags.containsKey(key)) info.tags[key] = value
                when (key) {
                    "TITLE" -> if (info.title == null) info.title = value
                    "ARTIST" -> if (info.artist == null) info.artist = value
                    "ALBUM" -> if (info.album == null) info.album = value
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun parsePicture(d: ByteArray, info: FlacInfo) {
        try {
            if (d.size < 32) return
            val type = be32(d, 0)
            var p = 4
            val mimeLen = be32(d, p)
            p += 4 + mimeLen
            val descLen = be32(d, p)
            p += 4 + descLen
            p += 16
            val dataLen = be32(d, p)
            p += 4
            if (mimeLen < 0 || descLen < 0 || dataLen <= 0 || p + dataLen > d.size) return
            // Prefer the front cover (type 3); otherwise keep the first picture.
            if (info.picture == null || (type == 3 && info.pictureType != 3)) {
                info.picture = d.copyOfRange(p, p + dataLen)
                info.pictureType = type
            }
        } catch (_: Throwable) {
        }
    }
}
