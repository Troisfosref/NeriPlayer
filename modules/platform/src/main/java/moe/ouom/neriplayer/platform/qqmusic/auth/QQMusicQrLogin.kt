package moe.ouom.neriplayer.platform.qqmusic.auth

// QQ OAuth sequence adapted from HGODLK/MusicOne (ShuyunR), GPL-3.0-or-later.
// https://github.com/HGODLK/MusicOne/tree/main/source-packs/qq

import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.parseQQCookies
import moe.ouom.neriplayer.platform.qqmusic.api.qqHash
import moe.ouom.neriplayer.platform.qqmusic.api.requireQQSuccess
import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellationException

class QQMusicQrSession internal constructor(
    val image: ByteArray,
    internal val signature: String,
    internal val cookies: QQMusicLoginCookies
) {
    override fun toString(): String = "QQMusicQrSession(redacted)"
}

enum class QQMusicQrStatus { WAITING_SCAN, WAITING_CONFIRM, EXPIRED, CANCELLED, SUCCESS }

class QQMusicQrResult(val status: QQMusicQrStatus, val cookie: String? = null) {
    override fun toString(): String = "QQMusicQrResult(status=$status)"
}

class QQMusicQrLogin(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).build()

    suspend fun create(): QQMusicQrSession {
        val cookies = QQMusicLoginCookies()
        val url = url("https://ssl.ptlogin2.qq.com/ptqrshow", mapOf(
            "appid" to "716027609", "e" to "2", "l" to "M", "s" to "3", "d" to "72", "v" to "4",
            "t" to System.currentTimeMillis().toString(), "daid" to "383", "pt_3rd_aid" to "100497308",
            "u1" to "https://graph.qq.com/oauth2.0/login_jump"))
        return http.newCall(request(url).build()).awaitResponse { response ->
            if (!response.isSuccessful) fail()
            val image = response.body.bytes()
            cookies.saveFromResponse(response.request.url, Cookie.parseAll(response.request.url, response.headers))
            val signature = cookies.loadForRequest(response.request.url).firstOrNull { it.name == "qrsig" }?.value ?: fail()
            if (image.size < 8 || !(image.take(3) == listOf(137, 80, 78).map(Int::toByte) ||
                    image.take(3) == listOf(255, 216, 255).map(Int::toByte) ||
                    image.take(3) == "GIF".toByteArray().toList())) fail()
            QQMusicQrSession(image, signature, cookies)
        }
    }

    suspend fun poll(session: QQMusicQrSession): QQMusicQrResult {
        val pollUrl = url("https://ssl.ptlogin2.qq.com/ptqrlogin", mapOf(
            "u1" to "https://graph.qq.com/oauth2.0/login_jump", "ptqrtoken" to qqHash(session.signature).toString(),
            "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1", "from_ui" to "1", "ptlang" to "2052",
            "action" to "0-0-${System.currentTimeMillis()}", "js_ver" to "20102616", "js_type" to "1",
            "pt_uistyle" to "40", "aid" to "716027609", "daid" to "383", "pt_3rd_aid" to "100497308", "has_onekey" to "1"))
        val callback = atStage("QQ-01") {
            parseQQQrCallback(execute(request(pollUrl).build(), session.cookies).text)
        }
        val status = when (callback.first) {
            66 -> QQMusicQrStatus.WAITING_SCAN
            67 -> QQMusicQrStatus.WAITING_CONFIRM
            65 -> QQMusicQrStatus.EXPIRED
            0 -> QQMusicQrStatus.SUCCESS
            else -> QQMusicQrStatus.CANCELLED
        }
        if (status != QQMusicQrStatus.SUCCESS) return QQMusicQrResult(status)
        val code = authorize(callback.second, session.cookies)
        return QQMusicQrResult(status, atStage("QQ-05") { exchange(code) })
    }

    private suspend fun authorize(redirect: String, cookies: QQMusicLoginCookies): String {
        val signed = atStage("QQ-02") {
            val checked = redirect.toHttpUrlOrNull() ?: fail()
            if (checked.scheme != "https" || checked.host !in setOf("ssl.ptlogin2.qq.com", "ssl.ptlogin2.graph.qq.com") ||
                checked.encodedPath != "/check_sig" || checked.port != 443 || checked.username.isNotEmpty() || checked.password.isNotEmpty()) fail()
            execute(request(redirect).build(), cookies)
        }
        // Activate only the expected QQConnect session, never follow arbitrary redirects with cookies.
        atStage("QQ-03") { execute(request(qqLoginJumpUrl(signed.location)).build(), cookies) }
        val skey = atStage("QQ-02") {
            cookies.loadForRequest("https://graph.qq.com/oauth2.0/authorize".toHttpUrl())
                .firstOrNull { it.name == "p_skey" }?.value ?: fail()
        }
        val state = UUID.randomUUID().toString().replace("-", "")
        val values = mapOf("response_type" to "code", "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends", "state" to state, "switch" to "", "from_ptlogin" to "1",
            "src" to "1", "update_auth" to "1", "openapi" to "1010_1030", "g_tk" to qqHash(skey, 5381).toString(),
            "auth_time" to System.currentTimeMillis().toString(), "ui" to UUID.randomUUID().toString())
        val form = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
        return atStage("QQ-04") {
            val result = execute(request("https://graph.qq.com/oauth2.0/authorize").post(form).build(), cookies)
            qqAuthorizationCode(result.location, state)
        }
    }

    private suspend fun exchange(code: String): String {
        val body = JSONObject().put("comm", JSONObject().put("g_tk", 5381).put("platform", "yqq").put("ct", 24).put("cv", 0))
            .put("qrLogin", JSONObject().put("module", "QQConnectLogin.LoginServer").put("method", "QQLogin")
                .put("param", JSONObject().put("code", code)))
        val response = execute(request("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        val root = JSONObject(response.text)
        requireQQSuccess(root)
        val result = root.optJSONObject("qrLogin") ?: fail()
        requireQQSuccess(result)
        val data = result.optJSONObject("data") ?: fail()
        val id = listOf("musicid", "str_musicid", "musicId", "userid", "uin")
            .firstNotNullOfOrNull { data.optString(it).takeIf { v -> v.isNotBlank() && v != "0" && v != "null" } } ?: fail()
        val key = listOf("musickey", "music_key", "qqmusic_key", "qm_keyst", "strMusicKey")
            .firstNotNullOfOrNull { data.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: fail()
        return mergeCookies("", response.cookies + mapOf("musicid" to id, "qqmusic_uin" to id,
            "uin" to "o${id.removePrefix("o").padStart(10, '0')}", "musickey" to key,
            "qqmusic_key" to key, "qm_keyst" to key, "authst" to key, "loginType" to "2"))
    }

    private class Reply(val text: String, val cookies: Map<String, String>, val location: String)

    private suspend fun execute(request: Request, cookies: QQMusicLoginCookies? = null): Reply {
        val scopedCookies = cookies?.loadForRequest(request.url).orEmpty()
        val outgoing = request.newBuilder().apply {
            if (scopedCookies.isNotEmpty()) header("Cookie", scopedCookies.joinToString("; ") { "${it.name}=${it.value}" })
        }.build()
        return http.newCall(outgoing).awaitResponse {
            if (it.code !in 200..399) fail()
            val received = Cookie.parseAll(it.request.url, it.headers)
            cookies?.saveFromResponse(it.request.url, received)
            val valid = received.filter { cookie -> cookie.expiresAt > System.currentTimeMillis() && cookie.value.isNotEmpty() }
            Reply(it.body.string(), valid.associate { cookie -> cookie.name to cookie.value }, it.header("Location").orEmpty())
        }
    }

    private fun request(url: String): Request.Builder = Request.Builder().url(url)
        .header("Referer", if (url.contains("ptlogin2")) "https://xui.ptlogin2.qq.com/"
            else if (url.contains("graph.qq.com")) "https://graph.qq.com/oauth2.0/login_jump" else "https://y.qq.com/")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")

    private fun url(base: String, values: Map<String, String>): String = base.toHttpUrl().newBuilder()
        .apply { values.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()

    private fun mergeCookies(seed: String, values: Map<String, String>): String =
        (parseQQCookies(seed) + values).entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun fail(): Nothing = throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)

    private suspend fun <T> atStage(code: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: QQMusicException) {
        throw QQMusicException(error.kind, code)
    } catch (_: Exception) {
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED, code)
    }
}

internal fun qqLoginJumpUrl(location: String): String {
    if (location.isBlank()) return "https://graph.qq.com/oauth2.0/login_jump"
    val uri = location.toHttpUrlOrNull() ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    if (uri.scheme != "https" || uri.host != "graph.qq.com" || uri.encodedPath != "/oauth2.0/login_jump" ||
        uri.port != 443 || uri.username.isNotEmpty() || uri.password.isNotEmpty())
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    return uri.toString()
}

internal fun parseQQQrCallback(raw: String): Pair<Int, String> {
    val callback = Regex("ptuiCB\\((.*?)\\)\\s*;?", RegexOption.DOT_MATCHES_ALL).find(raw)
        ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    val args = Regex("'((?:\\\\.|[^'\\\\])*)'").findAll(callback.groupValues[1])
        .map { it.groupValues[1].replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\") }.toList()
    return (args.firstOrNull()?.toIntOrNull() ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)) to args.getOrElse(2) { "" }
}

internal fun qqAuthorizationCode(location: String, state: String): String {
    val uri = location.toHttpUrlOrNull() ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    if (uri.scheme != "https" || uri.host != "y.qq.com" || uri.encodedPath != "/portal/wx_redirect.html" ||
        uri.port != 443 || uri.username.isNotEmpty() || uri.password.isNotEmpty() || uri.queryParameter("state") != state)
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    return uri.queryParameter("code")?.takeIf(String::isNotBlank)
        ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
}
