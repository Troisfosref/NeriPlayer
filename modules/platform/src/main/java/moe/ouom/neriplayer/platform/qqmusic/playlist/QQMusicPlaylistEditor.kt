package moe.ouom.neriplayer.platform.qqmusic.playlist

// PlaylistDetailWrite protocol adapted from HGODLK/MusicOne, GPL-3.0-or-later.
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicBrowseRepository
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.qqAccountId
import moe.ouom.neriplayer.platform.qqmusic.api.qqMusicKey
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import moe.ouom.neriplayer.platform.qqmusic.cache.loadQQMusicTrackPages
import org.json.JSONArray
import org.json.JSONObject

data class QQMusicPlaylistEditResult(
    val completed: List<SongItem>, val total: Int, val error: QQMusicException? = null
)

/** Only freshly resolved own directories may be edited. Route/cache metadata is not authority. */
class QQMusicPlaylistEditor(
    private val accounts: QQMusicAccountRepository,
    private val client: QQMusicClient,
    private val invalidate: suspend (QQMusicPlaylist) -> Unit = {}
) {
    private val mutex = Mutex()
    private val browse = QQMusicBrowseRepository(accounts, client)

    suspend fun writablePlaylists(): List<QQMusicPlaylist> {
        val cookie = credentials()
        val created = browse.created().filter { (it.directory ?: 0) > 0 }
        requireAccount(cookie)
        return (listOf(QQMusicPlaylist("favorites", "我喜欢", directory = 201)) + created).distinctBy { it.id }
    }

    /** Fresh complete membership: no stream URLs, cached rows or first-page-only guesses. */
    suspend fun memberMids(playlist: QQMusicPlaylist): Set<String> {
        val cookie = credentials()
        val tracks = loadQQMusicTrackPages { offset -> browse.tracks(playlist, offset) }
        requireAccount(cookie)
        return tracks.map { requireNotNull(QQMusicSongRef.from(it)).mid }.toSet()
    }

    suspend fun containsSelection(playlist: QQMusicPlaylist, songs: List<SongItem>): Boolean {
        val wanted = songs.map { requireNotNull(QQMusicSongRef.from(it)).mid }.toSet()
        return wanted.isNotEmpty() && memberMids(playlist).containsAll(wanted)
    }

    suspend fun update(id: String, songs: List<SongItem>, add: Boolean): QQMusicPlaylistEditResult = mutex.withLock {
        val cookie = credentials()
        val target = if (id == "favorites") QQMusicPlaylist(id, "我喜欢", directory = 201)
            else writablePlaylists().firstOrNull { it.id == id }
                ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        val unique = songs.distinctBy { QQMusicSongRef.from(it)?.mid ?: it.mediaUri }
        require(unique.isNotEmpty())
        // Resolve every catalog id before any write. Our SongItem.id is a compatibility hash.
        val refs = unique.map { song ->
            val ref = QQMusicSongRef.from(song) ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            val detail = client.songDetail(ref.mid, cookie)
            val songId = detail.optLong("id", detail.optLong("songid", 0))
            if (songId <= 0) throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            JSONObject().put("songId", songId)
                .put("songType", qqMusicPlaylistWriteType(detail.optInt("type", ref.songType)))
        }
        val completed = mutableListOf<SongItem>()
        var attempted = false
        try {
            for (chunk in unique.indices.toList().chunked(100)) {
                requireAccount(cookie)
                attempted = true
                try {
                    val response = client.post("req_0", "music.musicasset.PlaylistDetailWrite",
                        if (add) "AddSonglist" else "DelSonglist",
                        JSONObject().put("dirId", requireNotNull(target.directory)).put("tid", target.id.toLongOrNull() ?: 0L)
                            .put("bFmtUtf8", true).put("source", "normal")
                            .apply { if (target.id != "favorites") put("dirName", target.title) }
                            .put("v_songInfo", JSONArray(chunk.map { refs[it] })), cookie)
                    val ret = response.optInt("retCode", -1)
                    if (!response.has("retCode") || ret != 0) throw QQMusicException(
                        QQMusicException.Kind.REQUEST_FAILED, ret.takeIf { it > 0 }?.toString())
                    val batch = chunk.map { unique[it] }
                    if (add) completed += batch
                    else {
                        // QQ may acknowledge a no-op when the write type does not match.
                        // Confirm absence, allowing a short read-only consistency window.
                        var remaining = memberMids(target)
                        for (retry in 0 until 2) {
                            if (batch.none { requireNotNull(QQMusicSongRef.from(it)).mid in remaining }) break
                            delay(250L)
                            remaining = memberMids(target)
                        }
                        val removed = batch.filter { requireNotNull(QQMusicSongRef.from(it)).mid !in remaining }
                        completed += removed
                        if (removed.size != batch.size) return@withLock QQMusicPlaylistEditResult(
                            completed, unique.size, QQMusicException(QQMusicException.Kind.REQUEST_FAILED))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    return@withLock QQMusicPlaylistEditResult(completed, unique.size,
                        error as? QQMusicException ?: QQMusicException(QQMusicException.Kind.REQUEST_FAILED))
                }
            }
            QQMusicPlaylistEditResult(completed, unique.size)
        } finally {
            // A lost acknowledgement/cancellation can still follow a committed remote write.
            if (attempted) withContext(NonCancellable) {
                try { invalidate(target) } catch (_: Exception) { /* Revisions still force a network refresh. */ }
                accounts.notifyLibraryChanged()
            }
        }
    }

    private suspend fun credentials() = accounts.cookie().also {
        if (qqAccountId(it).isBlank() || qqMusicKey(it).isBlank())
            throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED)
    }
    private suspend fun requireAccount(cookie: String) {
        if (accounts.cookie() != cookie) throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED)
    }
}

/** QQ read types and playlist-write types differ; ordinary read type 1 must be written as 0.
 * Contract: L-1124/QQMusicApi commit 8d46735266ef81c5fbeccff15ddd211f7f966be6 (#306).
 * Playback identity keeps the original type; this conversion is only for playlist writes.
 */
internal fun qqMusicPlaylistWriteType(readType: Int): Int = when (readType) {
    0, 1 -> 0
    5, 111, 112, 113 -> readType
    else -> 1
}
