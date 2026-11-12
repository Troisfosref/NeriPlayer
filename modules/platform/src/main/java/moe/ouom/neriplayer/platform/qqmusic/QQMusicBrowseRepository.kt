package moe.ouom.neriplayer.platform.qqmusic

// Protocols adapted from HGODLK/MusicOne (ShuyunR), GPL-3.0-or-later.
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class QQMusicProfile(val id: String, val nickname: String, val avatar: String?)
data class QQMusicPlaylist(val id: String, val title: String, val cover: String? = null, val count: Int = 0, val directory: Int? = null)
data class QQMusicTrackPage(val songs: List<SongItem>, val nextOffset: Int?)

class QQMusicBrowseRepository(private val accounts: QQMusicAccountRepository, private val client: QQMusicClient) {
    private suspend fun credentials(): String = accounts.cookie().also {
        if (qqAccountId(it).isBlank() || qqMusicKey(it).isBlank()) throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED)
    }
    private suspend fun web(base: String, params: Map<String, String>, cookie: String): JSONObject {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        return client.getJson(url.toString(), cookie).also(::requireQQSuccess)
    }
    suspend fun profile(): QQMusicProfile {
        val cookie = credentials(); val id = qqAccountId(cookie)
        val root = web("https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", mapOf(
            "format" to "json", "g_tk" to qqHash(qqMusicKey(cookie),5381).toString(), "cid" to "205360838",
            "userid" to id, "loginUin" to id, "hostUin" to "0", "reqfrom" to "1", "needNewCode" to "0"), cookie)
        val data = root.optJSONObject("data"); val info = data?.optJSONObject("creator") ?: data ?: JSONObject()
        return QQMusicProfile(id, info.text("nick","nickname","name","hostname").ifBlank { "QQ 用户 $id" },
            info.text("headpic","avatar","avatarUrl","logo").takeIf(String::isNotBlank)?.https())
    }
    suspend fun liked(): QQMusicPlaylist {
        val c = credentials()
        val comm = qqComm(c).put("ct", 19).put("cv", 0).apply { remove("v") }
        val data = client.post("playlist", "music.srfDissInfo.DissInfo", "CgiGetDiss",
            JSONObject().put("disstid", 0L).put("dirid", 201)
                .put("song_begin", 0).put("song_num", 1).put("tag", true)
                .put("userinfo", true).put("orderlist", true).put("onlysonglist", false),
            c, commOverride = comm)
        val first = qqBrowseSongs(data.optJSONArray("songlist")).firstOrNull()
        return QQMusicPlaylist(
            id = "favorites", title = "我喜欢", cover = first?.coverUrl,
            count = data.optInt("total_song_num", 0).coerceAtLeast(0), directory = 201
        )
    }
    suspend fun created(): List<QQMusicPlaylist> {
        val c=credentials(); val id=qqAccountId(c); val result=mutableListOf<QQMusicPlaylist>(); var offset=0
        val seenIds = mutableSetOf<String>()
        while (true) {
            val data=web("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss",mapOf("hostuin" to id,"sin" to "$offset","size" to "100","format" to "json"),c).optJSONObject("data") ?: break
            val modern=data.optJSONArray("disslist");val legacy=data.optJSONArray("list")
            val count=maxOf(modern?.length() ?: 0,legacy?.length() ?: 0)
            val raw=JSONArray().apply { (modern.objects()+legacy.objects()).forEach { put(it) } }
            val page=raw.objects().mapNotNull(::qqBrowsePlaylist)
            if (offset > 0 && raw.length() > 0 && page.none { it.id !in seenIds }) {
                throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            }
            // Hidden system folders still count towards upstream pagination progress.
            seenIds += page.map { it.id }
            result += page.filter { !isQQHiddenSystemPlaylist(it) }
            if (count<100) break
            offset+=count
        }
        return result.distinctBy { it.id }
    }
    suspend fun collected(): List<QQMusicPlaylist> {
        val c=credentials(); val id=qqAccountId(c); val result=mutableListOf<QQMusicPlaylist>();var offset=0
        while (true) {
            val data=web("https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg", mapOf(
                "format" to "json","platform" to "yqq.json","loginUin" to id,"hostUin" to "0","userid" to id,
                "reqtype" to "3","sin" to "$offset","ein" to "${offset+99}","g_tk" to "5381","ct" to "20","cid" to "205360956"),c).optJSONObject("data") ?: break
            val raw=data.optJSONArray("cdlist") ?: break
            val page=raw.objects().mapNotNull(::qqBrowsePlaylist)
            if(offset>0 && raw.length()>0 && page.all { p -> result.any { it.id==p.id } }) throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            result+=page
            if(raw.length()<100)break
            offset+=raw.length()
        }
        return result.distinctBy { it.id }
    }
    suspend fun tracks(playlist: QQMusicPlaylist, offset: Int): QQMusicTrackPage {
        val c=credentials()
        if (playlist.id.startsWith("dir:")) {
            val root=web("https://s.plcloud.music.qq.com/fcgi-bin/fcg_musiclist_getinfo.fcg", mapOf(
                "uin" to qqAccountId(c),"dirid" to playlist.directory.toString(),"new" to "0","dirinfo" to "1",
                "miniportal" to "1","fromDir2Diss" to "1","mobile" to "1","from" to "$offset","to" to "${offset+99}","format" to "json","g_tk" to "5381"),c)
            return qqBrowseTrackPage(root,offset,"SongList",root.optInt("SongCount", root.optInt("TotalSongNum",-1)))
        }
        val comm=qqComm(c).put("ct",19).put("cv",0).apply{remove("v")}
        val data=client.post("playlist","music.srfDissInfo.DissInfo","CgiGetDiss",JSONObject()
            .put("disstid",playlist.id.toLongOrNull() ?: 0L).put("dirid",if(playlist.id=="favorites") 201 else 0)
            .put("song_begin",offset).put("song_num",100).put("tag",true).put("userinfo",true)
            .put("orderlist",true).put("onlysonglist",false),c,commOverride=comm)
        if(data.optInt("subcode",0)!=0) throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        return qqBrowseTrackPage(data,offset,"songlist",data.optInt("total_song_num",-1))
    }
    suspend fun daily(): List<SongItem> {
        val c=credentials();val v=parseQQCookies(c)
        val data=client.post("req_0","music.srfDissInfo.DissInfo","CgiGetDiss",JSONObject()
            .put("new_format",1).put("enc_host_uin",v["encryptUin"] ?: v["encrypt_uin"] ?: qqAccountId(c))
            .put("dirid",202).put("onlysonglist",1).put("optype",0).put("orderlist",0)
            .put("guid",(1_000_000_000L+qqHash(qqAccountId(c))).toString()).put("is_mobile",1)
            .put("local_time",System.currentTimeMillis()/1000).put("update_rtime",0),c)
        return qqBrowseSongs(data.optJSONArray("songlist"))
    }
    suspend fun radio(): List<SongItem> {
        val c=credentials()
        val data=client.post("songlist","music.radioProxy.MbTrackRadioSvr","get_radio_track",JSONObject()
            .put("id",99).put("num",20).put("from",0).put("scene",0).put("song_ids",JSONArray()),c,
            commOverride=JSONObject().put("uin",qqAccountId(c)).put("authst",qqMusicKey(c)).put("ct",19).put("cv",0).put("format","json"))
        return qqBrowseSongs(data.optJSONArray("tracks") ?: data.optJSONArray("track_list"))
    }
    suspend fun recommendations(): List<QQMusicPlaylist> {
        val c=credentials()
        val data=client.post("req_0","music.playlist.PlaylistSquare","GetRecommendFeed",JSONObject().put("From",0).put("Size",8),c)
        return data.optJSONArray("List").objects().mapNotNull { item ->
            val basic=(item.optJSONObject("Playlist") ?: item.optJSONObject("playlist"))?.optJSONObject("basic")
            basic?.let(::qqBrowsePlaylist)
        }.distinctBy { it.id }
    }
}
internal fun JSONObject.text(vararg names: String): String = names.firstNotNullOfOrNull { optString(it).takeIf { v -> v.isNotBlank() && v!="null" && v!="0" } }.orEmpty()
internal fun String.https(): String = if(startsWith("//")) "https:$this" else replaceFirst("http://","https://")
internal fun JSONArray?.objects(): List<JSONObject> = if(this==null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun qqBrowsePlaylist(j: JSONObject): QQMusicPlaylist? {
    val directory=j.optInt("dirid", j.optInt("dirId")).takeIf { it>0 }
    val id=j.text("tid","dissid","id").ifBlank { directory?.let { "dir:$it" }.orEmpty() }
    val name=j.text("title","diss_name","dissname","name")
    if(id.isBlank() || name.isBlank())return null
    val cover=j.optJSONObject("cover")?.text("medium_url","default_url","big_url","url").orEmpty()
        .ifBlank { j.text("diss_cover","cover_url","coverUrl","logo","imgurl","picurl","PicUrl") }
        .ifBlank { (j.opt("cover") as? String).orEmpty() }
    return QQMusicPlaylist(id,name,cover.takeIf(String::isNotBlank)?.https(),
        listOf("song_cnt","song_count","songnum","song_num").maxOf { j.optInt(it,0) },directory)
}
internal fun qqBrowseSongs(raw: JSONArray?): List<SongItem> = raw.objects().mapNotNull { j ->
    val song=j.optJSONObject("track") ?: j.optJSONObject("Track") ?: j.optJSONObject("songInfo") ?: j.optJSONObject("song") ?: j.optJSONObject("data") ?: j
    val packed=song.optString("data").split('|')
    val normalized=if(packed.size>7) JSONObject().put("mid",packed[0]).put("title",packed[1])
        .put("singer",JSONArray().put(JSONObject().put("name",packed[3])))
        .put("albummid",packed[4]).put("albumname",packed[5]).put("interval",packed[7].toLongOrNull() ?: 0)
    else song
    mapQQMusicSong(normalized)
}.distinctBy { it.audioId }
internal fun qqBrowseTrackPage(data: JSONObject, offset: Int, key: String, total: Int): QQMusicTrackPage {
    val raw=data.optJSONArray(key) ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    val end=offset+raw.length()
    return QQMusicTrackPage(qqBrowseSongs(raw),end.takeIf { raw.length()>0 && (if(total>=0) end<total else raw.length()>=100) && data.optInt("hasmore",1)!=0 })
}

internal fun isQQHiddenSystemPlaylist(playlist: QQMusicPlaylist): Boolean {
    val title = playlist.title.replace(Regex("\\s+"), "")
    return playlist.directory == 201 || listOf("我喜欢", "我喜欢的音乐", "QZone背景音乐", "本地上传")
        .any { it.equals(title, ignoreCase = true) }
}
