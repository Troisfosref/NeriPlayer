package moe.ouom.neriplayer.platform.qqmusic.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistPage
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistProfile
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONArray
import org.json.JSONObject

data class QQMusicArtistSnapshot(val profile: QQMusicArtistProfile,
    val songs: QQMusicArtistPage<SongItem>, val albums: QQMusicArtistPage<QQMusicPlaylist>)

class QQMusicArtistCacheRepository(private val store: PlatformPlaylistCacheRoomStore) {
    suspend fun read(mid: String): QQMusicCacheSnapshot<QQMusicArtistSnapshot>? = withContext(Dispatchers.IO) {
        store.read(QQMusicBrowseCacheRepository.PLATFORM,"0:artist:$mid")
            ?.takeIf { it.signatureSecondary==QQ_ARTIST_CACHE_VERSION }?.let {
            val snapshot=it.artistSnapshot()
            require(snapshot.profile.mid==mid)
            QQMusicCacheSnapshot(snapshot,it.savedAtMs)
        }
    }
    suspend fun save(snapshot: QQMusicArtistSnapshot) = withContext(Dispatchers.IO) {
        store.replaceBounded(snapshot.artistCacheRecord(System.currentTimeMillis()),100,50_000)
    }
}

internal fun QQMusicArtistSnapshot.artistCacheRecord(now: Long): PlatformPlaylistCacheRecord {
    val p=profile
    val meta=JSONObject().put("mid",p.mid).put("name",p.name).put("avatar",p.avatar)
        .put("background",p.background).put("alias",p.alias).put("introduction",p.introduction)
        .put("albums",JSONArray(requireNotNull(qqMusicLibraryCacheRecord(0L,"",albums.items,0,now).subtitle)))
    return qqMusicTrackCacheRecord(0L,"",songs.items,0,now).copy(
        cacheKey="0:artist:${p.mid}",kind="artist",subtitle=meta.toString(),
        sourceId=songs.nextOffset?.toLong(),playCount=albums.nextOffset?.toLong(),
        totalCount=songs.total,signaturePrimary=albums.total.toString(),signatureSecondary=QQ_ARTIST_CACHE_VERSION)
}

private const val QQ_ARTIST_CACHE_VERSION = "3"

internal fun PlatformPlaylistCacheRecord.artistSnapshot(): QQMusicArtistSnapshot {
    val meta=JSONObject(requireNotNull(subtitle))
    val profile=QQMusicArtistProfile(meta.getString("mid"),meta.getString("name"),
        meta.optString("avatar").takeIf(String::isNotBlank),meta.optString("background").takeIf(String::isNotBlank),
        meta.optString("alias"),meta.optString("introduction"))
    return QQMusicArtistSnapshot(profile,QQMusicArtistPage(qqSongs(),sourceId?.toInt(),totalCount),
        QQMusicArtistPage(copy(subtitle=meta.getJSONArray("albums").toString()).libraryPlaylists(),
            playCount?.toInt(),signaturePrimary?.toIntOrNull() ?: 0))
}
