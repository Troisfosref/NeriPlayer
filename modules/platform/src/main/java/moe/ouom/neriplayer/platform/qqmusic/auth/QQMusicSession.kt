package moe.ouom.neriplayer.platform.qqmusic.auth

// Credential renewal protocol adapted from HGODLK/MusicOne, GPL-3.0-or-later.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.qqmusic.api.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Keep renewal fields from login JSON, which are not necessarily Set-Cookie headers. */
internal fun qqSessionCookie(data: JSONObject, base: String = "", expectedAccount: String? = null): String {
    val id = listOf("musicid", "str_musicid", "musicId", "userid", "user_id", "uin")
        .firstNotNullOfOrNull { name -> data.optString(name).removePrefix("o").trimStart('0')
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) } }
        ?: expectedAccount ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    if (expectedAccount != null && id != expectedAccount)
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    val key = listOf("musickey", "music_key", "qqmusic_key", "qm_keyst", "strMusicKey")
        .firstNotNullOfOrNull { data.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } }
        ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    val values = parseQQCookies(base).toMutableMap()
    listOf("openid", "refresh_token", "access_token", "expired_at", "unionid", "refresh_key",
        "musickeyCreateTime", "keyExpiresIn", "loginType").forEach { name ->
        if (data.has(name) && !data.isNull(name)) values[name] = data.get(name).toString()
    }
    values.putAll(mapOf("musicid" to id, "str_musicid" to id, "qqmusic_uin" to id,
        "uin" to "o${id.padStart(10, '0')}", "musickey" to key, "qqmusic_key" to key,
        "qm_keyst" to key, "authst" to key))
    if (expectedAccount != null && key != qqMusicKey(base) &&
        (!data.has("musickeyCreateTime") || !data.has("keyExpiresIn"))) {
        // A new key must not inherit the old key's expiry when the server omits new timing.
        values.remove("musickeyCreateTime")
        values.remove("keyExpiresIn")
    }
    // Never allow JSON credential values to introduce additional cookie fields or headers.
    if (values.values.any { value -> value.any { it == ';' || it == '\r' || it == '\n' } })
        throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal fun qqSessionNeedsRefresh(cookie: String, nowSeconds: Long): Boolean {
    val values = parseQQCookies(cookie)
    val created = values["musickeyCreateTime"]?.toLongOrNull() ?: return false
    val lifetime = values["keyExpiresIn"]?.toLongOrNull() ?: return false
    if (created <= 0 || lifetime <= 0 || nowSeconds < created) return false
    return nowSeconds - created >= lifetime - minOf(600L, lifetime / 10)
}

/** Serializes renewal and never restores credentials replaced by logout or another login. */
internal class QQMusicSession(
    private val read: suspend () -> String,
    private val replace: suspend (String, String) -> Boolean,
    private val renew: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var lastAttemptAt: Long? = null
    private var lastAttemptAccount: String? = null
    private var lastAttemptCookie: String? = null
    private var lastFailure: QQMusicException? = null

    suspend fun cookie(rejectedCookie: String? = null): String = mutex.withLock {
        val current = read()
        if (current.isBlank() || (rejectedCookie != null && current != rejectedCookie)) return@withLock current
        val time = now()
        if (rejectedCookie == null && !qqSessionNeedsRefresh(current, time / 1000)) return@withLock current
        val account = qqAccountId(current)
        val previous = lastAttemptAt
        if (lastAttemptAccount == account && previous != null && time >= previous && time - previous < 300_000L) {
            if (rejectedCookie != null && current == lastAttemptCookie) lastFailure?.let { throw it }
            return@withLock current
        }
        lastAttemptAccount = account
        lastAttemptCookie = current
        lastAttemptAt = time
        lastFailure = null
        try {
            val updated = renew(current)
            if (updated != current) replace(current, updated)
        } catch (cancelled: CancellationException) {
            lastAttemptAt = null
            throw cancelled
        }
        catch (error: Exception) {
            // A failed renewal must not erase a possibly usable session or prevent offline use.
            if (read() == current && error is QQMusicException && error.kind in
                setOf(QQMusicException.Kind.LOGIN_REQUIRED, QQMusicException.Kind.VERIFICATION_REQUIRED)) {
                lastFailure = error
                if (rejectedCookie != null) throw error
            }
        }
        read()
    }
}

internal class QQMusicSessionClient(client: OkHttpClient) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

    suspend fun renew(cookie: String, deviceId: String): String {
        val account = qqAccountId(cookie)
        if (account.isBlank() || qqMusicKey(cookie).isBlank())
            throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED)
        val values = parseQQCookies(cookie)
        val param = JSONObject().put("openid", values["openid"].orEmpty())
            .put("access_token", values["access_token"].orEmpty())
            .put("refresh_token", values["refresh_token"].orEmpty())
            .put("expired_in", values["expired_at"]?.toLongOrNull() ?: 0L)
            .put("str_musicid", account).put("musicid", account.toLongOrNull() ?: 0L)
            .put("musickey", qqMusicKey(cookie)).put("unionid", values["unionid"].orEmpty())
            .put("refresh_key", values["refresh_key"].orEmpty()).put("loginMode", 2)
        val comm = qqComm(cookie).put("tmeLoginMethod", 3).put("QIMEI", "").put("QIMEI36", "")
            .put("OpenUDID", deviceId).put("OpenUDID2", deviceId).put("udid", deviceId).put("aid", deviceId)
            .put("os_ver", "14").put("phonetype", "NeriPlayer").put("rom", "NeriPlayer")
            .put("devicelevel", "34").put("newdevicelevel", "34")
        val body = JSONObject().put("comm", comm).put("session", JSONObject()
            .put("module", "music.login.LoginServer").put("method", "Login").put("param", param))
        val request = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("Cookie", cookie).header("Referer", "https://y.qq.com/")
            .header("User-Agent", "QQMusic 14090008(android 14)")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            val root = JSONObject(response.body.string())
            requireQQSuccess(root)
            val result = root.optJSONObject("session") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            requireQQSuccess(result)
            val data = result.optJSONObject("data") ?: throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
            val received = Cookie.parseAll(response.request.url, response.headers)
                .filter { it.expiresAt > System.currentTimeMillis() && it.value.isNotEmpty() }
                .associate { it.name to it.value }
            val seed = (parseQQCookies(cookie) + received).entries.joinToString("; ") { "${it.key}=${it.value}" }
            qqSessionCookie(data, seed, account)
        }
    }
}
