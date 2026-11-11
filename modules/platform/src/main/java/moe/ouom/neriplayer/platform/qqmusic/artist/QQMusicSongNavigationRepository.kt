package moe.ouom.neriplayer.platform.qqmusic.artist

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import org.json.JSONObject

data class QQMusicSongCollections(val album: QQMusicPlaylist?, val artists: List<QQMusicPlaylist>)

class QQMusicSongNavigationRepository(private val accounts: QQMusicAccountRepository, private val client: QQMusicClient) {
    private val lock=Mutex()
    private val cache=object : LinkedHashMap<String,Pair<Long,QQMusicSongCollections>>(32,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,QQMusicSongCollections>>?) = size>32
    }

    suspend fun resolve(song: SongItem, allowNetwork: Boolean = true): QQMusicSongCollections = lock.withLock {
        val mid=requireNotNull(QQMusicSongRef.from(song)).mid
        val now=System.nanoTime()
        cache[mid]?.takeIf { now-it.first<300_000_000_000L }?.let { return@withLock it.second }
        check(allowNetwork) { "QQ Music song metadata is not cached" }
        parseQQMusicSongCollections(client.songDetail(mid,accounts.cookie())).also {
            if(it.album!=null || it.artists.isNotEmpty()) cache[mid]=now to it
        }
    }
}

internal fun parseQQMusicSongCollections(detail: JSONObject): QQMusicSongCollections {
    val album=detail.optJSONObject("album")?.let { value ->
        val mid=value.artistText("mid","albummid","albumMid")
        val name=value.artistText("name","title")
        if(!validQQCollectionMid(mid) || name.isBlank()) null
        else QQMusicPlaylist("album:$mid",name,"https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg")
    }
    val singers=detail.optJSONArray("singer")
    val artists=(0 until (singers?.length() ?: 0)).mapNotNull { index ->
        val value=singers?.optJSONObject(index) ?: return@mapNotNull null
        val mid=value.artistText("mid","singermid","singerMid")
        val name=value.artistText("name","title")
        if(!validQQCollectionMid(mid) || name.isBlank()) null
        else QQMusicPlaylist("artist:$mid",name,"https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg")
    }.distinctBy { it.id }
    return QQMusicSongCollections(album,artists)
}
