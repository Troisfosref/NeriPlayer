package moe.ouom.neriplayer.platform.qqmusic.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import org.json.JSONArray
import org.json.JSONObject

/** Uses the existing rebuildable playlist-cache tables; no credentials or playback URLs are saved. */
class QQMusicBrowseCacheRepository(private val store: PlatformPlaylistCacheRoomStore) {
    suspend fun readLibrary(session: Long, section: String): QQMusicCacheSnapshot<List<QQMusicPlaylist>>? =
        withContext(Dispatchers.IO) {
            store.read(PLATFORM, qqMusicCacheKey(session, "library", section))?.let { record ->
                QQMusicCacheSnapshot(record.libraryPlaylists(), record.savedAtMs, record.cacheRevision())
            }
        }

    suspend fun saveLibrary(session: Long, section: String, data: List<QQMusicPlaylist>, revision: Int) =
        withContext(Dispatchers.IO) {
            save(qqMusicLibraryCacheRecord(session, section, data, revision, System.currentTimeMillis()))
        }

    suspend fun readTracks(session: Long, id: String): QQMusicCacheSnapshot<List<SongItem>>? =
        withContext(Dispatchers.IO) {
            store.read(PLATFORM, qqMusicCacheKey(session, "tracks", id))?.let { record ->
                QQMusicCacheSnapshot(record.qqSongs(), record.savedAtMs, record.cacheRevision())
            }
        }

    suspend fun saveTracks(session: Long, id: String, songs: List<SongItem>, revision: Int) =
        withContext(Dispatchers.IO) {
            save(qqMusicTrackCacheRecord(session, id, songs, revision, System.currentTimeMillis()))
        }

    /** Invalidate both library summaries and directory/public aliases after a cloud write. */
    suspend fun invalidatePlaylist(playlist: QQMusicPlaylist) = withContext(Dispatchers.IO) {
        val ids = setOfNotNull(playlist.id, playlist.directory?.let { "dir:$it" })
        store.clearWhere(PLATFORM) { entity ->
            entity.kind == "library" || ids.any { entity.cacheKey.endsWith(":tracks:$it") }
        }
    }

    private suspend fun save(record: PlatformPlaylistCacheRecord) =
        store.replaceBounded(record, maxRecords = 100, maxTracks = 50_000)

    private fun PlatformPlaylistCacheRecord.cacheRevision() = signatureSecondary?.toIntOrNull() ?: 0

    companion object { const val PLATFORM = "qqmusic" }
}

// The persisted login generation isolates private libraries, including after logout/account changes.
internal fun qqMusicCacheKey(session: Long, kind: String, id: String) = "$session:$kind:$id"

internal fun qqMusicLibraryCacheRecord(
    session: Long, section: String, data: List<QQMusicPlaylist>, revision: Int, now: Long
) = PlatformPlaylistCacheRecord(
    platform = QQMusicBrowseCacheRepository.PLATFORM,
    cacheKey = qqMusicCacheKey(session, "library", section), kind = "library", savedAtMs = now,
    signatureSecondary = revision.toString(), totalCount = data.size,
    subtitle = JSONArray().apply { data.forEach { playlist ->
        put(JSONObject().put("id", playlist.id).put("title", playlist.title).put("cover", playlist.cover)
            .put("count", playlist.count).put("directory", playlist.directory))
    } }.toString(), tracks = emptyList()
)

internal fun PlatformPlaylistCacheRecord.libraryPlaylists(): List<QQMusicPlaylist> {
    val array = JSONArray(requireNotNull(subtitle))
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        QQMusicPlaylist(item.getString("id"), item.getString("title"),
            if (item.isNull("cover")) null else item.getString("cover"), item.getInt("count"),
            if (item.isNull("directory")) null else item.getInt("directory"))
    }
}

internal fun qqMusicTrackCacheRecord(
    session: Long, id: String, songs: List<SongItem>, revision: Int, now: Long
) = PlatformPlaylistCacheRecord(
    platform = QQMusicBrowseCacheRepository.PLATFORM,
    cacheKey = qqMusicCacheKey(session, "tracks", id), kind = "tracks", savedAtMs = now,
    signatureSecondary = revision.toString(), trackCount = songs.size, totalCount = songs.size,
    hasMore = false, tracks = songs.map { song ->
        val ref = requireNotNull(QQMusicSongRef.from(song))
        PlatformPlaylistCacheTrackRecord(itemId = ref.numericId, itemKey = ref.mediaUri, audioId = ref.mid,
            name = song.name, artist = song.artist, album = song.album, albumId = song.albumId,
            durationMs = song.durationMs, coverUrl = song.coverUrl)
    }
)

internal fun PlatformPlaylistCacheRecord.qqSongs(): List<SongItem> = tracks.map { track ->
    val song = SongItem(requireNotNull(track.itemId), track.name, track.artist, track.album,
        track.albumId ?: 0L, track.durationMs, track.coverUrl, mediaUri = track.itemKey)
    val ref = requireNotNull(QQMusicSongRef.from(song))
    song.copy(channelId = QQMusicSongRef.CHANNEL, audioId = ref.mid, subAudioId = ref.mediaMid)
}
