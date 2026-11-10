package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicPlaybackTest {
    @Test fun `unavailable higher qualities fall back and carry the real quality label`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            requireNotNull(chain.request().body).writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            requests += body
            val prefix = body.getJSONObject("req_1").getJSONObject("param").getString("quality")
            val data = JSONObject().put("sip", JSONArray().put("https://isure.stream.qqmusic.qq.com/"))
                .put("midurlinfo", JSONArray().put(JSONObject().put("result", 0)
                    .put("purl", if (prefix == "M500") "M500actualFile.mp3?vkey=fixture" else "")))
            val result = JSONObject().put("code", 0).put("req_1", JSONObject().put("code", 0).put("data", data))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=456; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.LOSSLESS)
        val repository = QQMusicRepository(accounts, QQMusicClient(http))
        val song = requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalogMid","file":{"media_mid":"actualFile"}}""")))
        val result = repository.playback(song)
        assertEquals("standard", result.audioInfo?.qualityKey)
        assertEquals(listOf("F000", "F000", "M800", "M800", "M500"), requests.map {
            it.getJSONObject("req_1").getJSONObject("param").getString("quality") })
        assertTrue(requests.all { it.getJSONObject("req_1").getJSONObject("param").getString("uin") == "456" })
    }

    @Test fun `expired login stops fallback without issuing more tickets`() = runBlocking {
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":301}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=456; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.LOSSLESS)
        val song = requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalogMid","file":{"media_mid":"actualFile"}}""")))
        try {
            QQMusicRepository(accounts, QQMusicClient(http)).playback(song)
            fail("Expired session must not return a source")
        } catch (error: QQMusicException) {
            assertEquals(QQMusicException.Kind.LOGIN_REQUIRED, error.kind)
        }
        assertEquals(1, count)
    }
}
