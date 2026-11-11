package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto

internal suspend fun loadQQMusicLyrics(song: SongItem, translated: Boolean = false, preferWordTimed: Boolean = true, romanized: Boolean = false): List<LyricEntry> {
    val mid = QQMusicSongRef.from(song)?.mid ?: return emptyList()
    val repository = PlayerDependencies.repositories.qqMusicSearchApi
    val raw = if (romanized) repository.getNativeRomanizedLyrics(mid) else {
        val (original, translation) = repository.getNativeLyrics(mid)
        if (translated) translation else original
    }
    return parseNeteaseLyricsAuto(raw.orEmpty())
        .map { if (preferWordTimed) it else it.copy(words = null) }
}
