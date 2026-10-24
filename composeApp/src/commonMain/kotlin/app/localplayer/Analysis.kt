package app.localplayer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

private const val WINDOW = 8192

private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while ((j and bit) != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang)
        val wi = sin(ang)
        var i = 0
        while (i < n) {
            var cr = 1.0
            var ci = 0.0
            for (k in 0 until len / 2) {
                val a = i + k
                val b = a + len / 2
                val xr = re[b] * cr - im[b] * ci
                val xi = re[b] * ci + im[b] * cr
                re[b] = re[a] - xr
                im[b] = im[a] - xi
                re[a] += xr
                im[a] += xi
                val ncr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = ncr
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * Finds where the averaged spectrum drops off most steeply above 10 kHz.
 * Returns that frequency in kHz, or the Nyquist frequency when there is no clear cliff.
 */
fun spectrumCutoffKHz(windows: List<FloatArray>, sampleRate: Int): Float? {
    val usable = windows.filter { it.size >= WINDOW }
    if (usable.isEmpty() || sampleRate <= 0) return null
    val half = WINDOW / 2
    val nyquist = sampleRate / 2000f
    val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2.0 * PI * it / (WINDOW - 1)) }
    val acc = DoubleArray(half)
    for (w in usable) {
        val re = DoubleArray(WINDOW) { w[it] * hann[it] }
        val im = DoubleArray(WINDOW)
        fft(re, im)
        for (k in 0 until half) acc[k] += re[k] * re[k] + im[k] * im[k]
    }
    val db = DoubleArray(half) { 10.0 * log10(acc[it] / usable.size + 1e-20) }
    val smooth = DoubleArray(half)
    val r = 12
    for (k in 0 until half) {
        var s = 0.0
        var c = 0
        for (j in maxOf(0, k - r)..minOf(half - 1, k + r)) { s += db[j]; c++ }
        smooth[k] = s / c
    }
    val span = maxOf(1, (1000.0 * WINDOW / sampleRate).toInt())
    val start = (10000.0 * WINDOW / sampleRate).toInt()
    if (start + span >= half) return nyquist
    var bestDrop = 0.0
    var bestK = -1
    for (k in start until half - span) {
        val drop = smooth[k] - smooth[k + span]
        if (drop > bestDrop) { bestDrop = drop; bestK = k }
    }
    if (bestK < 0 || bestDrop < 20.0) return nyquist
    return (bestK + span / 2) * sampleRate / WINDOW / 1000f
}

fun buildReport(
    ext: String, rate: Int, bits: Int, ch: Int, size: Long, durMs: Long,
    lossless: Boolean, cutoff: Float?, eff: Int?,
): AudioReport {
    val kbps = if (durMs > 0) (size * 8 / durMs).toInt() else 0
    val hiRes = lossless && (rate > 48000 || bits > 16)
    val verdict = when {
        !lossless -> "Lossy format. Not lossless, so not hi-res."
        eff != null && bits > 16 && eff <= 16 ->
            "$bits-bit file, but only about $eff real bits are used. Probably padded from 16-bit."
        cutoff != null && rate > 48000 && cutoff < 23f ->
            "Hi-res container, but the sound stops near ${fmt1(cutoff)} kHz. Probably upsampled from CD quality or a lossy source."
        cutoff != null && rate in 40000..48000 && cutoff < 19.5f ->
            "Lossless container, but the sound stops near ${fmt1(cutoff)} kHz. Probably transcoded from a lossy source."
        hiRes -> "Looks like genuine hi-res audio."
        else -> "Looks like genuine lossless audio."
    }
    return AudioReport(
        container = ext.uppercase(), sampleRate = rate, bitDepth = bits, channels = ch,
        bitrateKbps = kbps, sizeBytes = size, lossless = lossless, hiRes = hiRes,
        cutoffKHz = cutoff, effectiveBits = eff, verdict = verdict,
    )
}
