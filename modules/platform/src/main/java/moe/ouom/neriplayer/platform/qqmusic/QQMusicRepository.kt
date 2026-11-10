package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.qqPlaybackUrls
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import org.json.JSONArray

data class QQMusicSearchPage(val songs: List<SongItem>, val hasMore: Boolean)

class QQMusicRepository(val accounts: QQMusicAccountRepository, private val client: QQMusicClient) {
    // Keep ticket requests sequential, including prefetches; never probe every search result.
    private val ticketMutex = Mutex()

    suspend fun song(mid: String): SongItem = mapQQMusicSong(client.songDetail(mid, accounts.cookie()))
        ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)

    suspend fun search(keyword: String, page: Int): QQMusicSearchPage {
        val data = client.search(keyword, page, accounts.cookie())
        val list = data.optJSONArray("list") ?: JSONArray()
        val total = data.optInt("totalnum", data.optInt("totalNum", -1))
        return QQMusicSearchPage(mapQQMusicSongs(list), list.length() > 0 &&
            if (total >= 0) page * 30 < total else list.length() >= 30)
    }

    suspend fun playback(song: SongItem): SongUrlResult.Success = ticketMutex.withLock {
        val ref = QQMusicSongRef.from(song) ?: throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL)
        val cookie = accounts.cookie()
        val mediaMid = ref.mediaMid.ifBlank {
            client.songDetail(ref.mid, cookie).optJSONObject("file")?.optString("media_mid").orEmpty()
        }
        for (quality in accounts.quality().fallback()) {
            val data = client.playback(ref.mid, mediaMid, ref.songType, quality, cookie)
            val primaryUrls = qqPlaybackUrls(data)
            val candidates = primaryUrls.ifEmpty {
                qqPlaybackUrls(client.playback(ref.mid, mediaMid, ref.songType, quality, cookie, compatibility = true))
            }
            val urls = candidates.filter { url ->
                java.net.URI(url).path.substringAfterLast('/').startsWith(quality.prefix)
            }
            if (urls.isEmpty()) continue
            return@withLock SongUrlResult.Success(
                url = urls.first(), candidateUrls = urls.drop(1), mimeType = quality.mimeType,
                durationMs = song.durationMs.takeIf { it > 0 },
                representationIdentity = "qqmusic:${ref.mid}:${quality.name}",
                audioInfo = PlaybackAudioInfo(source = PlaybackAudioSource.QQ_MUSIC,
                    qualityKey = quality.name.lowercase(), qualityLabel = quality.name,
                    mimeType = quality.mimeType, codecLabel = quality.extension.uppercase(), bitrateKbps = quality.bitrateKbps)
            )
        }
        throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL)
    }
}
