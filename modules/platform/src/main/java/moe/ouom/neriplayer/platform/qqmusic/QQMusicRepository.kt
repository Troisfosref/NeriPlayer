package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.CancellationException
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
import moe.ouom.neriplayer.platform.qqmusic.api.qqPlaybackNeedsValidation
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicQuality
import moe.ouom.neriplayer.platform.qqmusic.api.qqPlaybackRefusalCode
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import org.json.JSONArray
import org.json.JSONObject

data class QQMusicSearchPage(val songs: List<SongItem>, val hasMore: Boolean)

class QQMusicRepository(
    val accounts: QQMusicAccountRepository,
    private val client: QQMusicClient,
    private val validateFullLength: suspend (String, Long, QQMusicQuality) -> Boolean = client::isFullLength
) {
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

    suspend fun setLiked(song: SongItem, liked: Boolean) {
        val result = moe.ouom.neriplayer.platform.qqmusic.playlist.QQMusicPlaylistEditor(accounts, client)
            .update("favorites", listOf(song), liked)
        result.error?.let { throw it }
    }

    suspend fun playback(song: SongItem, preferredQualityOverride: QQMusicQuality? = null): SongUrlResult.Success = ticketMutex.withLock {
        var ref = QQMusicSongRef.from(song) ?: throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL)
        val cookie = accounts.cookie()
        var refreshed = false
        var expectedDuration = song.durationMs
        var durationRequested = false
        val mediaChecks = mutableMapOf<String, Boolean>()
        var upstreamRefusalCode: Int? = null
        suspend fun refresh() {
            val detail = client.songDetail(ref.mid, cookie)
            ref = QQMusicSongRef(ref.mid, detail.optJSONObject("file")?.optString("media_mid").orEmpty(), detail.optInt("type", ref.songType))
            if (expectedDuration <= 0) expectedDuration = detail.optLong("interval") * 1000
            refreshed = true
        }
        if (ref.mediaMid.isBlank()) refresh()
        repeat(2) {
            for (quality in (preferredQualityOverride ?: accounts.quality()).fallback()) {
                suspend fun candidates(compatibility: Boolean, enhanced: Boolean = false): List<String> = try {
                    val response = client.playback(ref.mid, ref.mediaMid, ref.songType, quality, cookie, compatibility, enhanced)
                    qqPlaybackRefusalCode(response)?.let { code ->
                        if (upstreamRefusalCode == null || code == 104003) upstreamRefusalCode = code
                    }
                    qqPlaybackUrls(response, includeUnverified = true).filter {
                            val name = java.net.URI(it).path.substringAfterLast('/')
                            // Signed CDN stream names can be prefixed with M8 (e.g. M8F000...flac)
                            // even though the requested file is F000...flac.
                            name.startsWith(quality.prefix, ignoreCase = true) || name.startsWith("M8${quality.prefix}", ignoreCase = true)
                        }.filter { url ->
                            if (!qqPlaybackNeedsValidation(url)) true else try {
                                if (expectedDuration <= 0 && !durationRequested) {
                                    durationRequested = true
                                    expectedDuration = client.songDetail(ref.mid, cookie).optLong("interval") * 1000
                                }
                                mediaChecks[url] ?: validateFullLength(url, expectedDuration, quality).also { mediaChecks[url] = it }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: QQMusicException) { throw error }
                            catch (_: Exception) { false }
                        }
                } catch (error: QQMusicException) {
                    if (error.kind != QQMusicException.Kind.REQUEST_FAILED) throw error
                    error.diagnosticCode?.toIntOrNull()?.let { code ->
                        if (upstreamRefusalCode == null || code == 104003) upstreamRefusalCode = code
                    }
                    emptyList()
                }
                val urls = candidates(false).ifEmpty {
                    candidates(true).ifEmpty {
                        candidates(true, enhanced = true).ifEmpty { candidates(false, enhanced = true) }
                    }
                }
                if (urls.isEmpty()) continue
                return@withLock SongUrlResult.Success(
                    url = urls.first(), candidateUrls = urls.drop(1), mimeType = quality.mimeType,
                    durationMs = expectedDuration.takeIf { it > 0 },
                    representationIdentity = "qqmusic:${ref.mid}:${quality.name}",
                    audioInfo = PlaybackAudioInfo(source = PlaybackAudioSource.QQ_MUSIC,
                        qualityKey = quality.name.lowercase(), qualityLabel = quality.name,
                        mimeType = quality.mimeType, codecLabel = quality.extension.uppercase(), bitrateKbps = quality.bitrateKbps)
                )
            }
            if (refreshed) throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL, upstreamRefusalCode?.toString())
            val old = ref
            refresh()
            if (ref == old) throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL, upstreamRefusalCode?.toString())
        }
        throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL, upstreamRefusalCode?.toString())
    }
}
