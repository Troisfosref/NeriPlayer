package moe.ouom.neriplayer.core.player.download.lyrics

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef

internal data class QQMusicDownloadLyrics(
    val original: String?,
    val translated: String?,
    val romanized: String?
)

/** Lyric downloads need the lyric bundle, not an additional song-metadata request. */
internal suspend fun resolveQQMusicDownloadLyrics(
    song: SongItem,
    fetchLyrics: suspend (String) -> Pair<String?, String?>,
    fetchRomanized: suspend (String) -> String?
): QQMusicDownloadLyrics {
    var original = song.matchedLyric?.takeIf(String::isNotBlank)
    var translated = song.matchedTranslatedLyric?.takeIf(String::isNotBlank)
    var romanized = song.matchedRomanizedLyric?.takeIf(String::isNotBlank)
    val fetchOriginal = song.matchedLyric == null
    val fetchTranslation = song.matchedTranslatedLyric == null
    val fetchRoma = song.matchedRomanizedLyric == null && (fetchOriginal || fetchTranslation)
    val mid = QQMusicSongRef.from(song)?.mid
    if (mid != null) {
        if (fetchOriginal || fetchTranslation) {
            val remote = fetchLyrics(mid)
            if (fetchOriginal) original = remote.first?.takeIf(String::isNotBlank)
            if (fetchTranslation) translated = remote.second?.takeIf(String::isNotBlank)
        }
        if (fetchRoma) romanized = fetchRomanized(mid)?.takeIf(String::isNotBlank)
    }
    return QQMusicDownloadLyrics(original, translated, romanized)
}
