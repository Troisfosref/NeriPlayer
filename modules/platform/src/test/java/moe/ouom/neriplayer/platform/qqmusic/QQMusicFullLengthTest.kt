package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.*
import okio.Buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicFullLengthTest {
    @Test fun `wifiurl with src is accepted only after actual duration validation`() = runBlocking {
        var validated = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"req_1":{"code":0,"data":{"sip":["https://isure.stream.qqmusic.qq.com/"],"midurlinfo":[{"result":0,"purl":"","wifiurl":"M500file.mp3?src=redirect&vkey=fixture"}]}}}""".toResponseBody()).build()
        }.build()
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.STANDARD)
        val song = requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalog","interval":240,"file":{"media_mid":"file"}}""")))
        val result = QQMusicRepository(accounts, QQMusicClient(http)) { url, expected, quality ->
            validated++
            assertTrue(url.contains("src=redirect"))
            assertEquals(240000L, expected)
            assertEquals(QQMusicQuality.STANDARD, quality)
            true
        }.playback(song)
        assertTrue(result.url.contains("M500file.mp3"))
        assertEquals(1, validated)
    }

    @Test fun `rejected preview continues to clean web compatibility`() = runBlocking {
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val buffer=Buffer();requireNotNull(chain.request().body).writeTo(buffer)
            val request=JSONObject(buffer.readUtf8()).getJSONObject("req_1")
            val platform=request.getJSONObject("param").getString("platform")
            val path=if(platform=="23") "M500file.mp3?src=preview" else "M500file.mp3?vkey=full"
            val body=JSONObject("""{"code":0,"req_1":{"code":0,"data":{"midurlinfo":[{"result":0}]}}}""")
            body.getJSONObject("req_1").getJSONObject("data").getJSONArray("midurlinfo").getJSONObject(0).put("purl",path)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody()).build()
        }.build()
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.STANDARD)
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalog","interval":240,"file":{"media_mid":"file"}}""")))
        val result=QQMusicRepository(accounts,QQMusicClient(http)) { _, _, _ -> false }.playback(song)
        assertEquals(2,calls)
        assertFalse(result.url.contains("src="))
    }

    @Test fun `short or unmeasurable media does not count as a full song`() {
        assertFalse(qqMediaDurationMatches(30000, 240000))
        assertFalse(qqMediaDurationMatches(0, 240000))
        assertFalse(qqMediaDurationMatches(240000, 0))
        assertTrue(qqMediaDurationMatches(239000, 240000))
        assertFalse(qqMediaDurationMatches(230000, 240000))
    }

    @Test fun `FLAC streaminfo extracts duration without downloading the song`() {
        val header=ByteArray(42)
        "fLaC".toByteArray().copyInto(header)
        header[7]=34
        var packed=(44100L shl 44) or (44100L*240)
        for(i in 25 downTo 18) {header[i]=packed.toByte();packed=packed ushr 8}
        assertEquals(240000L,qqFlacDuration(header))
        assertNull(qqFlacDuration(header.copyOf(20)))
        header[0]=0
        assertNull(qqFlacDuration(header))
    }
}
