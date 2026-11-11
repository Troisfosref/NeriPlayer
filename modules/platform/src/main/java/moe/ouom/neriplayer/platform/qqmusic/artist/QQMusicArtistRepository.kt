package moe.ouom.neriplayer.platform.qqmusic.artist

// Artist contracts adapted from HGODLK/MusicOne, GPL-3.0-or-later.
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.qqAccountId
import moe.ouom.neriplayer.platform.qqmusic.api.qqComm
import moe.ouom.neriplayer.platform.qqmusic.api.qqMusicKey
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import moe.ouom.neriplayer.platform.qqmusic.qqBrowseSongs
import org.json.JSONArray
import org.json.JSONObject

data class QQMusicArtistProfile(val mid: String, val name: String, val avatar: String?,
    val background: String?, val alias: String = "", val introduction: String = "")
data class QQMusicArtistPage<T>(val items: List<T>, val nextOffset: Int?, val total: Int)

class QQMusicArtistRepository(private val accounts: QQMusicAccountRepository, private val client: QQMusicClient) {
    private suspend fun request(module: String, method: String, params: JSONObject): JSONObject {
        val cookie = accounts.cookie()
        val homepage = module == "music.UnifiedHomepage.UnifiedHomepageSrv"
        val comm = JSONObject().put("uin", qqAccountId(cookie).ifBlank { "0" }).put("format", "json")
            .put("ct", if (homepage) 11 else 19).put("cv", if (homepage) 20080000 else 0)
            .put("authst", qqMusicKey(cookie)).put("tmeLoginType",qqComm(cookie).optInt("tmeLoginType",2))
        if (homepage) comm.put("v", 20080000)
        return client.post("artist", module, method, params, cookie, commOverride=comm)
    }

    suspend fun songs(mid: String, offset: Int): QQMusicArtistPage<SongItem> {
        requireArtistRequest(mid, offset)
        val data=request("music.musichallSong.SongListInter", "GetSingerSongList",
            JSONObject().put("singerMid",mid).put("order",1).put("num",50).put("begin",offset))
        val raw=data.optJSONArray("songList") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        val total=data.optInt("totalNum",-1)
        return QQMusicArtistPage(qqBrowseSongs(raw), artistNextOffset(offset,raw.length(),total),
            total.takeIf { it>=0 } ?: (offset+raw.length()))
    }

    suspend fun albums(mid: String, offset: Int): QQMusicArtistPage<QQMusicPlaylist> {
        requireArtistRequest(mid, offset)
        val data=request("music.musichallAlbum.AlbumListServer", "GetAlbumList",
            JSONObject().put("singerMid",mid).put("order",0).put("num",50).put("begin",offset))
        val raw=data.optJSONArray("albumList") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        val total=data.optInt("total",data.optInt("totalNum",-1))
        val items=(0 until raw.length()).mapNotNull { index ->
            raw.optJSONObject(index)?.let { qqArtistAlbum(it.optJSONObject("albumInfo") ?: it) }
        }.distinctBy { it.id }
        return QQMusicArtistPage(items,artistNextOffset(offset,raw.length(),total),
            total.takeIf { it>=0 } ?: (offset+raw.length()))
    }

    suspend fun profile(artist: QQMusicPlaylist): QQMusicArtistProfile = coroutineScope {
        val mid=artist.id.removePrefix("artist:")
        requireArtistRequest(mid,0)
        val introduction=async {
            optional {
                request("music.UnifiedHomepage.UnifiedHomepageSrv","GetHomepageTabDetail",
                    JSONObject().put("SingerMid",mid).put("IsQueryTabDetail",1).put("TabID","wiki")
                        .put("PageNum",0).put("PageSize",10).put("Order",0))
            }?.let(::parseQQArtistIntroduction).orEmpty()
        }
        val header=request("music.UnifiedHomepage.UnifiedHomepageSrv","GetHomepageHeader",
            JSONObject().put("SingerMid",mid)).optJSONObject("Info")
            ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        parseQQArtistProfile(artist,header,introduction.await())
    }
}

private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
catch(cancelled: CancellationException) { throw cancelled }
catch(_: Exception) { null }

internal fun requireArtistRequest(mid: String, offset: Int) {
    require(mid.matches(Regex("[A-Za-z0-9]{1,64}")) && mid !in setOf("0","null") && offset>=0)
}

private fun artistNextOffset(offset: Int, count: Int, total: Int): Int? = (offset+count).takeIf {
    count>0 && if(total>=0) it<total else count>=50
}

internal fun qqArtistAlbum(value: JSONObject): QQMusicPlaylist? {
    val mid=value.artistText("albumMid","album_mid","mid")
    val name=value.artistText("albumName","album_name","name")
    if(!validQQCollectionMid(mid) || name.isBlank()) return null
    return QQMusicPlaylist("album:$mid",name,
        value.artistText("albumPic","picUrl").qqArtistImage() ?: "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg",
        maxOf(value.optInt("songNum",0),value.optInt("song_count",0),value.optInt("songnum",0)))
}

internal fun validQQCollectionMid(mid: String): Boolean =
    mid.matches(Regex("[A-Za-z0-9]{1,64}")) && mid !in setOf("0","null")

internal fun JSONObject.artistText(vararg keys: String): String = keys.firstNotNullOfOrNull {
    optString(it).takeIf { value -> value.isNotBlank() && value!="null" }
}.orEmpty()

private fun String.qqArtistImage(): String? = when {
    startsWith("https://") -> this
    startsWith("http://") -> replaceFirst("http://","https://")
    startsWith("//") -> "https:$this"
    else -> null
}

internal fun parseQQArtistProfile(artist: QQMusicPlaylist, header: JSONObject, introduction: String): QQMusicArtistProfile {
    val mid=artist.id.removePrefix("artist:")
    val singer=header.optJSONObject("Singer")
    val base=header.optJSONObject("BaseInfo")
    val avatar=base?.artistText("Avatar")?.qqArtistImage() ?: singer?.artistText("SingerPic")?.qqArtistImage()
        ?: artist.cover ?: "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"
    val photos=singer?.optJSONArray("SingerImageLists")
    var background=base?.artistText("BackgroundImage")?.qqArtistImage()
    if(background==null && photos!=null) for(i in 0 until photos.length()) {
        val images=photos.optJSONObject(i)?.optJSONArray("ImageList") ?: continue
        for(j in 0 until images.length()) {
            val image=images.optJSONObject(j) ?: continue
            if(image.optInt("BlockCarousel")==1) continue
            val imageMid=image.artistText("ImageMid")
            background=if(imageMid.matches(Regex("[A-Za-z0-9_]+")))
                "https://y.gtimg.cn/music/photo_new/T065R1080x1920M000$imageMid.jpg"
            else image.artistText("HighResolutionImage").qqArtistImage()
            if(background!=null) break
        }
        if(background!=null) break
    }
    return QQMusicArtistProfile(mid,singer?.artistText("Name").orEmpty().ifBlank { artist.title },avatar,
        background ?: avatar,singer?.artistText("Alias","EnglishName").orEmpty(),introduction)
}

internal fun parseQQArtistIntroduction(data: JSONObject): String {
    val list=data.optJSONObject("IntroductionTab")?.optJSONArray("List") ?: JSONArray()
    return (0 until list.length()).flatMap { i ->
        val fields=list.optJSONObject(i)?.optJSONArray("SingerInfoList") ?: JSONArray()
        (0 until fields.length()).mapNotNull { fields.optJSONObject(it)?.artistText("Content")?.takeIf(String::isNotBlank) }
    }.joinToString("\n\n")
}
