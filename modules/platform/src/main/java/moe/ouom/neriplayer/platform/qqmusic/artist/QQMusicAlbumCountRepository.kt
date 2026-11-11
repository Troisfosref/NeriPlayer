package moe.ouom.neriplayer.platform.qqmusic.artist

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.qqAccountId
import moe.ouom.neriplayer.platform.qqmusic.api.qqMusicKey
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import org.json.JSONObject

/** Artist album lists may return zero placeholders. Check the authoritative song-list total. */
class QQMusicAlbumCountRepository internal constructor(
    private val fetch: suspend (String) -> Int,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(accounts: QQMusicAccountRepository,client: QQMusicClient) : this({ mid ->
        val cookie=accounts.cookie()
        val comm=JSONObject().put("uin",qqAccountId(cookie).ifBlank { "0" }).put("format","json")
            .put("ct",19).put("cv",0).put("authst",qqMusicKey(cookie))
        val page=client.post("album_count","music.musichallAlbum.AlbumSongList","GetAlbumSongList",
            JSONObject().put("albumMid",mid).put("begin",0).put("num",100).put("order",2),
            cookie,commOverride=comm)
        val total=page.optInt("totalNum",-1)
        val items=requireNotNull(page.optJSONArray("songList"))
        require(total>=0 && total>=items.length()) { "QQ Music returned an inconsistent album count" }
        total
    })
    private val guard=Mutex()
    private val permits=Semaphore(3)
    private val albumLocks=List(16) { Mutex() }
    private val cache=object : LinkedHashMap<String,Pair<Long,Int>>(128,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,Int>>?) = size>128
    }

    suspend fun count(mid: String): Int {
        require(validQQCollectionMid(mid))
        // Bounded per-key lock stripes share lookups without tying another caller to the owner's cancellation.
        return albumLocks[(mid.hashCode() and Int.MAX_VALUE)%albumLocks.size].withLock {
            val known=guard.withLock { cache[mid]?.takeIf { now()>=it.first && now()-it.first<300_000L } }
            if(known!=null) return@withLock known.second
            val total=permits.withPermit { fetch(mid) }
            require(total>=0)
            guard.withLock { cache[mid]=now() to total }
            total
        }
    }
}
