package app.localplayer

private val TS = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val WORD = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

private fun toMs(m: String, s: String, f: String): Long {
    val frac = when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
    return m.toLong() * 60000 + s.toLong() * 1000 + frac
}

/** Parses normal LRC and enhanced (word-by-word) LRC like [00:12.00]<00:12.00>Hello <00:12.50>world */
fun parseLrc(text: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    for (raw in text.lines()) {
        val line = raw.trim()
        val stamps = TS.findAll(line).toList()
        if (stamps.isEmpty()) continue
        val body = line.substring(stamps.last().range.last + 1)
        val wm = WORD.findAll(body).toList()
        val words = wm.mapIndexed { i, m ->
            val end = if (i + 1 < wm.size) wm[i + 1].range.first else body.length
            LyricWord(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]), body.substring(m.range.last + 1, end))
        }
        val plain = if (words.isEmpty()) body.trim() else words.joinToString("") { it.text }.trim()
        for (s in stamps) {
            out += LyricLine(toMs(s.groupValues[1], s.groupValues[2], s.groupValues[3]), plain, words)
        }
    }
    return out.sortedBy { it.timeMs }
}

fun currentLine(lines: List<LyricLine>, posMs: Long): Int {
    var r = -1
    for (i in lines.indices) { if (lines[i].timeMs <= posMs) r = i else break }
    return r
}
