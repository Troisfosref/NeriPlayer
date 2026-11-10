package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto

internal suspend fun loadQQMusicLyrics(song: SongItem, translated: Boolean = false): List<LyricEntry> {
    val mid = QQMusicSongRef.from(song)?.mid ?: return emptyList()
    val info = PlayerDependencies.repositories.qqMusicSearchApi.getNativeSongInfo(mid)
    return parseNeteaseLyricsAuto((if (translated) info.translatedLyric else info.lyric).orEmpty())
}
