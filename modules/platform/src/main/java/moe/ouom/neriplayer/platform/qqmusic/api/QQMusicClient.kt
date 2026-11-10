package moe.ouom.neriplayer.platform.qqmusic.api

// QQ request formats adapted from HGODLK/MusicOne (ShuyunR), GPL-3.0-or-later.

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class QQMusicClient(private val client: OkHttpClient) {
    private val guid = UUID.randomUUID().toString().filter(Char::isDigit).take(10).padEnd(10, '0')

    suspend fun search(keyword: String, page: Int, cookie: String): JSONObject {
        require(keyword.isNotBlank() && page > 0)
        val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", keyword).addQueryParameter("format", "json")
            .addQueryParameter("p", page.toString()).addQueryParameter("n", "30").build()
        val json = getJson(url.toString(), cookie)
        requireQQSuccess(json)
        return json.optJSONObject("data")?.optJSONObject("song")
            ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    }

    suspend fun songDetail(mid: String, cookie: String): JSONObject {
        val data = post("songinfo", "music.pf_song_detail_svr", "get_song_detail_yqq",
            JSONObject().put("song_mid", mid), cookie)
        return data.optJSONObject("track_info") ?: data.optJSONObject("trackInfo")
            ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    }

    suspend fun playback(mid: String, mediaMid: String, type: Int, quality: QQMusicQuality, cookie: String, compatibility: Boolean = false, enhanced: Boolean = false): JSONObject {
        val names = qqFilenames(quality.prefix, quality.extension, mid, mediaMid)
        val params = JSONObject().put("guid", guid).put("songmid", JSONArray(names.map { mid }))
            .put("songtype", JSONArray(names.map { type })).put("filename", JSONArray(names))
            .put("uin", qqAccountId(cookie).ifBlank { "0" }).put("loginflag", 1)
            .put("platform", if (compatibility || enhanced) "20" else "23").put("quality", quality.prefix).put("h5queryversion", 1)
            .put("nettype", "").put("cms", 0).put("firstlogin", 1).put("newver", 1).put("nohash", 0)
        if (compatibility || enhanced) listOf("quality", "h5queryversion", "nettype", "cms", "firstlogin", "newver", "nohash").forEach(params::remove)
        if (enhanced && !compatibility) params.put("xcdn", 1)
        // Some accounts can retrieve full streams with EVkey when legacy vkey returns no purl.
        val enhancedComm = if (enhanced) JSONObject().put("ct", 24).put("cv", 0)
            .put("format", "json").put("uin", qqAccountId(cookie).ifBlank { "0" })
            .put("authst", qqMusicKey(cookie))
            .put("tmeLoginType", qqComm(cookie).optInt("tmeLoginType", 2)) else null
        return post("req_1",
            when {
                enhanced && compatibility -> "vkey.GetVkeyServer"
                enhanced -> "music.vkey.GetEVkey"
                else -> "music.vkey.GetVkey"
            },
            when {
                enhanced && compatibility -> "CgiGetVkey"
                enhanced -> "CgiGetEVkey"
                else -> "UrlGetVkey"
            },
            params, cookie, compatibility || enhanced, enhancedComm)
    }

    internal suspend fun post(key: String, module: String, method: String, params: JSONObject, cookie: String, compatibility: Boolean = false, commOverride: JSONObject? = null): JSONObject {
        val comm = commOverride ?: if (compatibility) JSONObject().put("cv", 4747474).put("ct", 24).put("format", "json")
            .put("inCharset", "utf-8").put("outCharset", "utf-8").put("notice", 0)
            .put("platform", "yqq.json").put("needNewCode", 1).put("uin", qqAccountId(cookie).ifBlank { "0" }) else qqComm(cookie)
        val body = JSONObject().put("comm", comm).put(key,
            JSONObject().put("module", module).put("method", method).put("param", params))
        val request = request("https://u.y.qq.com/cgi-bin/musicu.fcg", cookie)
            .apply { if (compatibility) header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val root = execute(request)
        requireQQSuccess(root)
        val result = root.optJSONObject(key) ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
        requireQQSuccess(result)
        return result.optJSONObject("data") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    }

    internal suspend fun getJson(url: String, cookie: String): JSONObject = execute(request(url, cookie)
        .header("Referer", "https://y.qq.com/portal/search.html")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build())

    private fun request(url: String, cookie: String): Request.Builder = Request.Builder().url(url)
        .header("Referer", "https://y.qq.com/").header("User-Agent", "QQMusic 14090008(android 14)")
        .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }

    private suspend fun execute(request: Request): JSONObject = client.newCall(request).awaitResponse {
        if (!it.isSuccessful) throw QQMusicException(when(it.code) {
            401 -> QQMusicException.Kind.LOGIN_REQUIRED
            403, 429 -> QQMusicException.Kind.VERIFICATION_REQUIRED
            else -> QQMusicException.Kind.REQUEST_FAILED
        })
        val raw = it.body.string().trim()
        // Legacy search may wrap JSON in a callback; never evaluate that callback.
        try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED) }
    }
}
