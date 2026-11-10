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
    @Test fun `enhanced endpoint is first and includes authenticated full stream parameters`() = runBlocking {
        val calls=mutableListOf<JSONObject>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val b=Buffer();requireNotNull(chain.request().body).writeTo(b)
            val body=JSONObject(b.readUtf8());calls+=body
            val data=JSONObject().put("sip",JSONArray().put("https://isure.stream.qqmusic.qq.com/"))
                .put("midurlinfo",JSONArray().put(JSONObject().put("result",0).put("purl","M500mediaFile.mp3?vkey=fixture")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("code",0).put("req_1",JSONObject().put("code",0).put("data",data)).toString().toResponseBody()).build()
        }.build()
        val account=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(account.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(account.quality()).thenReturn(QQMusicQuality.STANDARD)
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalogMid","file":{"media_mid":"mediaFile"}}""")))
        val result=QQMusicRepository(account,QQMusicClient(http)).playback(song)
        assertEquals("standard",result.audioInfo?.qualityKey)
        assertEquals(1,calls.size)
        assertEquals("music.vkey.GetEVkey",calls[0].getJSONObject("req_1").getString("module"))
        assertEquals("CgiGetEVkey",calls[0].getJSONObject("req_1").getString("method"))
        assertEquals(1,calls[0].getJSONObject("req_1").getJSONObject("param").getInt("xcdn"))
        assertEquals(24,calls[0].getJSONObject("comm").getInt("ct"))
    }

    @Test fun `unavailable higher qualities fall back and carry the real quality label`() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            requireNotNull(chain.request().body).writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            requests += body
            val prefix = body.getJSONObject("req_1").getJSONObject("param").getJSONArray("filename").getString(0).take(4)
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
        assertEquals(listOf("F000", "F000", "F000", "F000", "M800", "M800", "M800", "M800", "M500"), requests.map {
            it.getJSONObject("req_1").getJSONObject("param").getJSONArray("filename").getString(0).take(4) })
        assertTrue(requests.all { it.getJSONObject("req_1").getJSONObject("param").getString("uin") == "456" })
    }

    @Test fun `signed M8F000 CDN name resolves as full lossless audio`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val data = JSONObject().put("sip", JSONArray().put("https://isure.stream.qqmusic.qq.com/"))
                .put("midurlinfo", JSONArray().put(JSONObject().put("result", 0)
                    .put("purl", "M8F000signed.flac?vkey=fixture")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("code",0).put("req_1",
                    JSONObject().put("code",0).put("data",data)).toString().toResponseBody()).build()
        }.build()
        val account=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(account.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(account.quality()).thenReturn(QQMusicQuality.LOSSLESS)
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalog","file":{"media_mid":"file"}}""")))
        assertEquals("lossless",QQMusicRepository(account,QQMusicClient(http)).playback(song).audioInfo?.qualityKey)
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
    @Test fun `Aimer sample tries clean web compatibility when mobile returns the wrong quality`() = runBlocking {
        val requests=mutableListOf<JSONObject>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val b=Buffer();requireNotNull(chain.request().body).writeTo(b)
            val body=JSONObject(b.readUtf8());requests+=body
            val p=body.getJSONObject("req_1").getJSONObject("param")
            val compat=p.getString("platform")=="20"
            val enhanced=body.getJSONObject("req_1").getString("method")=="CgiGetEVkey"
            val path=if(compat && !enhanced) "F000002sg0214BnHfE.flac?vkey=fixture" else "M500002sg0214BnHfE.mp3?vkey=fixture"
            val data=JSONObject().put("sip",JSONArray().put("https://isure.stream.qqmusic.qq.com/"))
                .put("midurlinfo",JSONArray().put(JSONObject().put("result",0).put("purl",path)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("code",0).put("req_1",JSONObject().put("code",0).put("data",data)).toString().toResponseBody()).build()
        }.build()
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.LOSSLESS)
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"001exNOg2q7sDE","title":"茜さす","file":{"media_mid":"002sg0214BnHfE"}}""")))
        val result=QQMusicRepository(accounts,QQMusicClient(http)).playback(song)
        assertEquals("lossless",result.audioInfo?.qualityKey)
        assertEquals(2,requests.size)
        assertEquals("CgiGetVkey",requests.last().getJSONObject("req_1").getString("method"))
        val param=requests.last().getJSONObject("req_1").getJSONObject("param")
        assertEquals("20",param.getString("platform"));assertFalse(param.has("quality"));assertFalse(param.has("h5queryversion"))
        assertEquals("001exNOg2q7sDE",param.getJSONArray("songmid").getString(0))
        assertEquals("F000002sg0214BnHfE.flac",param.getJSONArray("filename").getString(0))
    }

    @Test fun `stale media MID is refreshed once after all original tickets are empty`() = runBlocking {
        var details=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val b=Buffer();requireNotNull(chain.request().body).writeTo(b);val body=JSONObject(b.readUtf8())
            val result=JSONObject().put("code",0)
            if(body.has("songinfo")) {
                details++
                result.put("songinfo",JSONObject().put("code",0).put("data",JSONObject()
                    .put("track_info",JSONObject().put("mid","001exNOg2q7sDE").put("type",0)
                        .put("file",JSONObject().put("media_mid","002sg0214BnHfE")))))
            } else {
                val p=body.getJSONObject("req_1").getJSONObject("param")
                val valid=p.getJSONArray("filename").getString(0).contains("002sg0214BnHfE")
                result.put("req_1",JSONObject().put("code",0).put("data",JSONObject()
                    .put("sip",JSONArray().put("https://isure.stream.qqmusic.qq.com/"))
                    .put("midurlinfo",JSONArray().put(JSONObject().put("result",0)
                        .put("purl",if(valid) "M500002sg0214BnHfE.mp3?vkey=fixture" else "")))))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result.toString().toResponseBody()).build()
        }.build()
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        Mockito.`when`(accounts.quality()).thenReturn(QQMusicQuality.STANDARD)
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"001exNOg2q7sDE","file":{"media_mid":"staleFile"}}""")))
        assertTrue(QQMusicRepository(accounts,QQMusicClient(http)).playback(song).url.contains("002sg0214BnHfE"))
        assertEquals(1,details)
    }

}
