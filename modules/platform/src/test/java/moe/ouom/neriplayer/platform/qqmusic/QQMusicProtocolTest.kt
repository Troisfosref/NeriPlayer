package moe.ouom.neriplayer.platform.qqmusic

import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.parseQQQrCallback
import moe.ouom.neriplayer.platform.qqmusic.auth.qqAuthorizationCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QQMusicProtocolTest {
    @Test fun `music account has priority over legacy QQ uin`() {
        val cookie = "uin=o0000000123; musicid=456; qqmusic_key=fixture"
        assertEquals("456", qqAccountId(cookie))
        assertEquals("fixture", qqMusicKey(cookie))
        val comm = qqComm(cookie)
        assertEquals("456", comm.getString("qq"))
        assertEquals("fixture", comm.getString("authst"))
    }

    @Test fun `media MID owns filenames when different from catalog MID`() {
        val names = qqFilenames("M800", "mp3", "catalogMid", "fileMid")
        assertEquals(listOf("M800fileMid.mp3", "M800fileMidfileMid.mp3", "M800catalogMid.mp3", "M800catalogMidcatalogMid.mp3"), names)
        assertFalse(names.any { it.contains("catalogMid") })
    }

    @Test fun `only authorized complete CDN candidates survive parsing`() {
        fun response(base: String, purl: String, result: Int = 0) = JSONObject()
            .put("sip", JSONArray().put(base))
            .put("midurlinfo", JSONArray().put(JSONObject().put("purl", purl).put("result", result)))
        assertEquals(listOf("https://isure.stream.qqmusic.qq.com/M800file.mp3?vkey=fixture"),
            qqPlaybackUrls(response("http://isure.stream.qqmusic.qq.com/", "M800file.mp3?vkey=fixture")))
        assertTrue(qqPlaybackUrls(response("https://attacker.example/", "M800file.mp3")).isEmpty())
        assertTrue(qqPlaybackUrls(response("https://isure.stream.qqmusic.qq.com/", "https://qq.com.attacker.example/M800file.mp3")).isEmpty())
        assertTrue(qqPlaybackUrls(response("https://isure.stream.qqmusic.qq.com/", "M800file.mp3?src=preview")).isEmpty())
        assertTrue(qqPlaybackUrls(response("https://isure.stream.qqmusic.qq.com/", "C400trial.m4a")).isEmpty())
        assertTrue(qqPlaybackUrls(response("https://isure.stream.qqmusic.qq.com/", "M800file.mp3", 1)).isEmpty())
    }

    @Test fun `upstream ticket refusal can be diagnosed without exposing URL`() {
        val response = JSONObject().put("midurlinfo", JSONArray().put(
            JSONObject().put("result", 104003).put("purl", "")))
        assertEquals(104003, qqPlaybackRefusalCode(response))
        assertNull(qqPlaybackRefusalCode(JSONObject().put("midurlinfo", JSONArray())))
    }

    @Test fun `missing sip entries get a Tencent CDN fallback`() {
        val response = JSONObject().put("sip", JSONArray()).put("midurlinfo", JSONArray().put(
            JSONObject().put("result", 0).put("purl", "M500song.mp3?vkey=fixture")))
        assertEquals(listOf("https://ws.stream.qqmusic.qq.com/M500song.mp3?vkey=fixture"), qqPlaybackUrls(response))
    }

    @Test fun `quality fallback never upgrades the requested tier`() {
        assertEquals(listOf(QQMusicQuality.LOSSLESS, QQMusicQuality.EXHIGH, QQMusicQuality.STANDARD), QQMusicQuality.LOSSLESS.fallback())
        assertEquals(listOf(QQMusicQuality.STANDARD), QQMusicQuality.STANDARD.fallback())
    }

    @Test fun `QR callback is parsed without evaluating JavaScript`() {
        assertEquals(67 to "", parseQQQrCallback("ptuiCB('67','0','','0','scanned','');"))
        assertEquals(0 to "https://ssl.ptlogin2.qq.com/check_sig?a=1",
            parseQQQrCallback("ptuiCB('0','0','https://ssl.ptlogin2.qq.com/check_sig?a=1','0','ok','');"))
    }

    @Test fun `OAuth requires the original state and redirect host`() {
        val base = "https://y.qq.com/portal/wx_redirect.html?code=fixture&state=expected"
        assertEquals("fixture", qqAuthorizationCode(base, "expected"))
        assertThrows(QQMusicException::class.java) { qqAuthorizationCode(base, "other") }
        assertThrows(QQMusicException::class.java) { qqAuthorizationCode(base.replace("y.qq.com", "attacker.example"), "expected") }
        assertThrows(QQMusicException::class.java) { qqAuthorizationCode("https://y.qq.com/portal/wx_redirect.html?code=fixture", "expected") }
    }
}
