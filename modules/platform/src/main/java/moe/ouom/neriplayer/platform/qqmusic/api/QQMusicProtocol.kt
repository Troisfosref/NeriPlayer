package moe.ouom.neriplayer.platform.qqmusic.api

// Request formats adapted from HGODLK/MusicOne's QQ source (ShuyunR), GPL-3.0-or-later.
// https://github.com/HGODLK/MusicOne/tree/main/source-packs/qq

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class QQMusicException(val kind: Kind, val diagnosticCode: String? = null) : IOException(kind.name) {
    enum class Kind { LOGIN_REQUIRED, NO_PLAY_URL, REQUEST_FAILED, VERIFICATION_REQUIRED }
}

internal fun parseQQCookies(raw: String): Map<String, String> = raw.split(';').mapNotNull {
    val pair = it.trim().split('=', limit = 2)
    if (pair.size == 2 && pair[0].matches(Regex("[A-Za-z0-9_]+")) &&
        pair[1].none { ch -> ch == '\r' || ch == '\n' }) pair[0] to pair[1] else null
}.toMap()

internal fun qqAccountId(cookie: String): String {
    val values = parseQQCookies(cookie)
    return listOf("str_musicid", "musicid", "qqmusic_uin", "uin", "wxuin")
        .firstNotNullOfOrNull { name -> values[name]?.trim()?.removePrefix("o")?.trimStart('0')
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } }.orEmpty()
}

internal fun qqMusicKey(cookie: String): String {
    val values = parseQQCookies(cookie)
    return listOf("musickey", "qqmusic_key", "qm_keyst", "authst")
        .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }.orEmpty()
}

internal fun qqHash(value: String, seed: Int = 0): Int =
    value.fold(seed) { hash, ch -> hash * 33 + ch.code } and Int.MAX_VALUE

internal fun qqComm(cookie: String): JSONObject = JSONObject()
    .put("ct", 11).put("cv", 14090008).put("v", 14090008).put("chid", "10003505")
    .put("qq", qqAccountId(cookie).ifBlank { "0" }).put("authst", qqMusicKey(cookie))
    .put("tmeAppID", "qqmusic").put("format", "json")
    .put("tmeLoginType", parseQQCookies(cookie)["loginType"]?.toIntOrNull()
        ?: if (qqMusicKey(cookie).startsWith("W_X")) 1 else 2)

internal fun requireQQSuccess(json: JSONObject) {
    when (json.optInt("code", -1)) {
        0 -> Unit
        301, 1000, 2000, 20271 -> throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED)
        20276 -> throw QQMusicException(QQMusicException.Kind.VERIFICATION_REQUIRED)
        else -> throw QQMusicException(QQMusicException.Kind.REQUEST_FAILED)
    }
}

enum class QQMusicQuality(val prefix: String, val extension: String, val bitrateKbps: Int?) {
    LOSSLESS("F000", "flac", null), EXHIGH("M800", "mp3", 320), STANDARD("M500", "mp3", 128);

    fun fallback(): List<QQMusicQuality> = entries.drop(ordinal)
    val mimeType: String get() = if (extension == "flac") "audio/flac" else "audio/mpeg"
}

internal fun qqFilenames(prefix: String, extension: String, mid: String, mediaMid: String): List<String> =
    if (mediaMid.isNotBlank()) listOf("$prefix$mediaMid.$extension", "$prefix$mediaMid$mediaMid.$extension").distinct()
    else listOf("$prefix$mid$mid.$extension", "$prefix$mid.$extension").distinct()

/** Only HTTPS Tencent music CDN URLs; API cookies are never sent to the CDN. */
internal fun qqPlaybackUrls(data: JSONObject): List<String> {
    val bases = data.optJSONArray("sip") ?: JSONArray().put("https://isure.stream.qqmusic.qq.com/")
    val items = data.optJSONArray("midurlinfo") ?: return emptyList()
    return buildList {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("result", 0) != 0) continue
            val purl = item.optString("purl").takeIf(String::isNotBlank) ?: continue
            for (j in 0 until bases.length()) {
                val base = bases.optString(j).replaceFirst("http://", "https://")
                    .let { if (it.startsWith("//")) "https:$it" else it }
                val url = base.toHttpUrlOrNull()?.resolve(purl)?.newBuilder()?.scheme("https")?.build() ?: continue
                if (url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                    (url.host.endsWith(".qq.com") || url.host.endsWith(".qqmusic.com")) &&
                    !url.encodedPath.substringAfterLast('/').startsWith("C400") &&
                    url.queryParameterNames.none { it.equals("src", ignoreCase = true) }) add(url.toString())
            }
        }
    }.distinct()
}
