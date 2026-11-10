package moe.ouom.neriplayer.platform.qqmusic.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Ephemeral browser cookies for one QR challenge; never persisted as music credentials. */
internal class QQMusicLoginCookies : CookieJar {
    private val values = linkedMapOf<Triple<String, String, String>, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = Triple(cookie.name, cookie.domain, cookie.path)
            if (cookie.value.isEmpty() || cookie.expiresAt <= now) values.remove(key)
            else values[key] = cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        values.entries.removeAll { it.value.expiresAt <= now }
        return values.values.filter { it.matches(url) }
            .sortedWith(compareByDescending<Cookie> { it.path.length }.thenByDescending { it.domain.length })
    }
}
