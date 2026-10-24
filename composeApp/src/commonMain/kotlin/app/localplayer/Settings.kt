package app.localplayer

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED"),
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fullscreenCover: Boolean = false,
    val showDetails: Boolean = true,
    val coverRadius: Int = 36,
    val automix: Boolean = false,
    val fadeSec: Int = 4,
    val haptics: Boolean = false,
    val onlineLyrics: Boolean = true,
    val wordSync: Boolean = true,
    val lyricsSize: Int = 24,
    val lyricsCenter: Boolean = false,
)

fun Settings.encode(): String = buildString {
    append("theme=").append(themeMode.name).append('\n')
    append("fullscreen=").append(fullscreenCover).append('\n')
    append("details=").append(showDetails).append('\n')
    append("coverRadius=").append(coverRadius).append('\n')
    append("automix=").append(automix).append('\n')
    append("fadeSec=").append(fadeSec).append('\n')
    append("haptics=").append(haptics).append('\n')
    append("onlineLyrics=").append(onlineLyrics).append('\n')
    append("wordSync=").append(wordSync).append('\n')
    append("lyricsSize=").append(lyricsSize).append('\n')
    append("lyricsCenter=").append(lyricsCenter).append('\n')
}

fun decodeSettings(text: String?): Settings {
    if (text == null) return Settings()
    val map = text.lines().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i > 0) line.substring(0, i) to line.substring(i + 1).trim() else null
    }.toMap()
    val d = Settings()
    return Settings(
        themeMode = ThemeMode.entries.firstOrNull { it.name == map["theme"] } ?: d.themeMode,
        fullscreenCover = map["fullscreen"]?.let { it == "true" } ?: d.fullscreenCover,
        showDetails = map["details"]?.let { it != "false" } ?: d.showDetails,
        coverRadius = map["coverRadius"]?.toIntOrNull()?.coerceIn(0, 48) ?: d.coverRadius,
        automix = map["automix"]?.let { it == "true" } ?: d.automix,
        fadeSec = map["fadeSec"]?.toIntOrNull()?.coerceIn(1, 12) ?: d.fadeSec,
        haptics = map["haptics"]?.let { it == "true" } ?: d.haptics,
        onlineLyrics = map["onlineLyrics"]?.let { it != "false" } ?: d.onlineLyrics,
        wordSync = map["wordSync"]?.let { it != "false" } ?: d.wordSync,
        lyricsSize = map["lyricsSize"]?.toIntOrNull()?.coerceIn(16, 40) ?: d.lyricsSize,
        lyricsCenter = map["lyricsCenter"]?.let { it == "true" } ?: d.lyricsCenter,
    )
}
