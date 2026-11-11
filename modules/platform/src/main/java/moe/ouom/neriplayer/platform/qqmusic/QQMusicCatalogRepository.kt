package moe.ouom.neriplayer.platform.qqmusic

// Search and collection protocols adapted from HGODLK/MusicOne, GPL-3.0-or-later.
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.qqAccountId
import moe.ouom.neriplayer.platform.qqmusic.api.qqComm
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import org.json.JSONArray
import org.json.JSONObject

enum class QQMusicSearchType(val apiType: Int, val field: String) {
    SONG(0, "song"), ALBUM(2, "album"), ARTIST(1, "singer"), PLAYLIST(3, "songlist")
}

data class QQMusicSearchCollection(
    val id: String,
    val title: String,
    val type: QQMusicSearchType,
    val cover: String?,
    val subtitle: String = "",
    val count: Int = 0
) {
    fun playlist(): QQMusicPlaylist = QQMusicPlaylist(
        id = when (type) {
            QQMusicSearchType.ALBUM -> "album:$id"
            QQMusicSearchType.ARTIST -> "artist:$id"
            else -> id
        },
        title = title, cover = cover, count = count
    )
}

data class QQMusicCatalogPage(
    val songs: List<SongItem> = emptyList(),
    val collections: List<QQMusicSearchCollection> = emptyList(),
    val hasMore: Boolean = false
)

class QQMusicCatalogRepository(
    private val accounts: QQMusicAccountRepository,
    private val client: QQMusicClient,
    private val legacySongSearch: suspend (String, Int) -> QQMusicSearchPage = QQMusicRepository(accounts, client)::search
) {
    suspend fun search(query: String, type: QQMusicSearchType, page: Int): QQMusicCatalogPage {
        require(query.isNotBlank() && page > 0)
        return try {
            val cookie = accounts.cookie()
            val comm = qqComm(cookie).put("ct", 19).put("cv", 1859).put("v", 1859)
                .put("uin", qqAccountId(cookie).ifBlank { "0" })
            val data = client.post("search", "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                qqCatalogSearchParams(query, type, page), cookie, commOverride = comm)
            parseQQCatalogPage(data, type, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the previously working song endpoint as a fallback; never disguise other errors as empty results.
            if (type != QQMusicSearchType.SONG) throw error
            val legacy = legacySongSearch(query, page)
            QQMusicCatalogPage(songs = legacy.songs, hasMore = legacy.hasMore)
        }
    }

    suspend fun collectionTracks(id: String, offset: Int): QQMusicTrackPage {
        require(offset >= 0)
        val mid = id.substringAfter(':')
        require(mid.isNotBlank() && mid.all(Char::isLetterOrDigit))
        val cookie = accounts.cookie()
        val album = id.startsWith("album:")
        require(album || id.startsWith("artist:"))
        val params = if (album) JSONObject().put("albumMid", mid).put("begin", offset).put("num", 100).put("order", 2)
            else JSONObject().put("singerMid", mid).put("begin", offset).put("num", 50).put("order", 1)
        val data = client.post("collection",
            if (album) "music.musichallAlbum.AlbumSongList" else "music.musichallSong.SongListInter",
            if (album) "GetAlbumSongList" else "GetSingerSongList", params, cookie)
        val raw = data.optJSONArray("songList") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        val total = data.optInt("totalNum", -1)
        val end = offset + raw.length()
        return QQMusicTrackPage(qqBrowseSongs(raw), end.takeIf {
            raw.length() > 0 && if (total >= 0) end < total else raw.length() >= if (album) 100 else 50
        })
    }
}

internal fun qqCatalogSearchParams(query: String, type: QQMusicSearchType, page: Int): JSONObject = JSONObject()
    .put("query", query.trim()).put("search_type", type.apiType).put("page_num", page)
    .put("num_per_page", 30).put("sin", (page - 1) * 30).put("ein", page * 30)
    .put("highlight", 0).put("cat", 2).put("grp", 1).put("nqc_flag", 0)
    .put("remoteplace", "txt.mqq.all").put("multi_zhida", 0).put("sem", 0)

internal fun parseQQCatalogPage(data: JSONObject, type: QQMusicSearchType, page: Int): QQMusicCatalogPage {
    val meta = data.optJSONObject("meta")
    if (data.optInt("code", 0) != 0 || (meta?.optInt("is_filter", 0) ?: 0) < 0) {
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    }
    val body = data.optJSONObject("body") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    val container = body.optJSONObject(type.field)
    val raw = body.optJSONArray(type.field) ?: container?.optJSONArray("list")
        ?: container?.optJSONArray("items") ?: JSONArray()
    val total = meta?.optInt("sum", -1) ?: data.optInt("total_num", -1)
    val more = when {
        meta?.has("nextpage") == true -> meta.optInt("nextpage", -1) >= 0
        total >= 0 -> page * 30 < total
        else -> raw.length() >= 30
    }
    return QQMusicCatalogPage(
        songs = if (type == QQMusicSearchType.SONG) qqBrowseSongs(raw) else emptyList(),
        collections = if (type == QQMusicSearchType.SONG) emptyList() else raw.objects()
            .mapNotNull { parseQQCatalogCollection(it, type) }.distinctBy { it.id },
        hasMore = raw.length() > 0 && more
    )
}

private fun JSONObject.catalogText(vararg keys: String): String = text(*keys)
    .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
    .replace("&lt;", "<").replace("&gt;", ">").trim()

private fun parseQQCatalogCollection(value: JSONObject, type: QQMusicSearchType): QQMusicSearchCollection? {
    val id = when (type) {
        QQMusicSearchType.ALBUM -> value.catalogText("albummid", "albumMID", "albumMid", "mid")
        QQMusicSearchType.ARTIST -> value.catalogText("singerMID", "singerMid", "mid")
        else -> value.catalogText("dissid", "id")
    }
    val title = when (type) {
        QQMusicSearchType.ALBUM -> value.catalogText("name", "albumName")
        QQMusicSearchType.ARTIST -> value.catalogText("singerName", "name")
        else -> value.catalogText("dissname", "name")
    }
    if (id.isBlank() || id == "0" || !id.all(Char::isLetterOrDigit) || title.isBlank()) return null
    val image = value.catalogText("albumPic", "singerPic", "imgurl", "pic", "logo")
        .ifBlank { when (type) {
            QQMusicSearchType.ALBUM -> "https://y.gtimg.cn/music/photo_new/T002R500x500M000$id.jpg"
            QQMusicSearchType.ARTIST -> "https://y.gtimg.cn/music/photo_new/T001R300x300M000$id.jpg"
            else -> ""
        } }.takeIf(String::isNotBlank)?.https()
    val subtitle = if (type == QQMusicSearchType.ALBUM) value.catalogText("singer", "singerName")
        else value.catalogText("nickname").ifBlank { value.optJSONObject("creator")?.catalogText("name").orEmpty() }
    return QQMusicSearchCollection(id, title, type, image, subtitle,
        listOf("song_count", "songnum", "songNum", "song_cnt").maxOf { value.optInt(it, 0) }.coerceAtLeast(0))
}
