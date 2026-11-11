package moe.ouom.neriplayer.data.model.qqmusic

import moe.ouom.neriplayer.data.model.SongItem
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Catalog MID is the identity; media MID identifies the audio file and can differ. */
data class QQMusicSongRef(val mid: String, val mediaMid: String = "", val songType: Int = 0) {
    init {
        require(mid.matches(MID_PATTERN) && mid != "0" && mid != "null")
        require(mediaMid.isEmpty() || mediaMid.matches(MID_PATTERN))
        require(songType >= 0)
    }

    val mediaUri: String get() = "qqmusic://song/$mid?mediaMid=$mediaMid&type=$songType"
    val numericId: Long get() = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
        .digest("qqmusic:$mid".toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE

    companion object {
        const val CHANNEL = "qqmusic"
        private val MID_PATTERN = Regex("[A-Za-z0-9]{1,64}")

        fun from(song: SongItem): QQMusicSongRef? {
            return try {
                if (song.channelId == CHANNEL && song.mediaUri.isNullOrBlank()) {
                    return QQMusicSongRef(song.audioId.orEmpty(), song.subAudioId.orEmpty())
                }
                val uri = URI(song.mediaUri ?: "")
                if (uri.scheme != "qqmusic" || uri.rawAuthority != "song" || uri.fragment != null) null
                else {
                    val query = uri.rawQuery.orEmpty().split('&').associate {
                        val pair = it.split('=', limit = 2)
                        pair[0] to pair.getOrElse(1) { "" }
                    }
                    QQMusicSongRef(uri.path.removePrefix("/"), query["mediaMid"].orEmpty(), query["type"]?.toIntOrNull() ?: 0)
                }
            } catch (_: IllegalArgumentException) { null }
            catch (_: java.net.URISyntaxException) { null }
        }
    }
}

fun SongItem.isQQMusicSong(): Boolean = channelId == QQMusicSongRef.CHANNEL ||
    mediaUri?.startsWith("qqmusic://") == true
