package moe.ouom.neriplayer.ui.screen.qqmusic

import java.nio.ByteBuffer
import java.security.MessageDigest
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist

internal const val QQ_MUSIC_ALBUM_FAVORITE_SOURCE = "qqmusicAlbum"

internal data class QQMusicFavoriteTarget(val id: Long, val source: String, val browseId: String? = null)

/** Retain the real album MID for navigation; the numeric key is only for local storage. */
internal fun QQMusicPlaylist.favoriteTarget(): QQMusicFavoriteTarget? = when {
    id.startsWith("album:") -> {
        val mid = id.removePrefix("album:").takeIf(::validQQAlbumMid)
        mid?.let {
            val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest("qqmusic:album:$it".toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE
            QQMusicFavoriteTarget(key, QQ_MUSIC_ALBUM_FAVORITE_SOURCE, it)
        }
    }
    id.startsWith("artist:") || id in setOf("daily", "radio") -> null
    else -> QQMusicFavoriteTarget(summary().id, "qqmusic")
}

internal fun FavoritePlaylist.toQQMusicAlbumPlaylist(): QQMusicPlaylist? {
    if (source != QQ_MUSIC_ALBUM_FAVORITE_SOURCE) return null
    val mid = browseId?.takeIf(::validQQAlbumMid) ?: return null
    return QQMusicPlaylist("album:$mid", name, coverUrl, trackCount)
}

private val QQAlbumMidPattern = Regex("[A-Za-z0-9]{1,64}")
private fun validQQAlbumMid(mid: String): Boolean = mid.matches(QQAlbumMidPattern) && mid != "0" && mid != "null"
