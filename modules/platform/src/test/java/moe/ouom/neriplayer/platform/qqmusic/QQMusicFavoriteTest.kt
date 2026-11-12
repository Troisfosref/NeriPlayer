package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicFavoriteTest {
    @Test fun `cloud likes use real catalog id and invalidate after a write even when the acknowledgement is lost`() = runBlocking {
        val calls=mutableListOf<JSONObject>()
        var retCode:Int?=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val b=Buffer();requireNotNull(chain.request().body).writeTo(b)
            val body=JSONObject(b.readUtf8());calls+=body
            val result=if(body.has("songinfo")) JSONObject("""{"code":0,"songinfo":{"code":0,"data":{"track_info":{"id":202084654,"type":0}}}}""")
            else JSONObject().put("code",0).put("req_0",JSONObject().put("code",0).put("data",
                JSONObject().apply {retCode?.let {put("retCode",it)}}))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toString().toResponseBody()).build()
        }.build()
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        val song=requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"001exNOg2q7sDE","file":{"media_mid":"file"}}""")))
        val repo=QQMusicRepository(accounts,QQMusicClient(http))
        repo.setLiked(song,true)
        val request=calls.last().getJSONObject("req_0")
        assertEquals("AddSonglist",request.getString("method"))
        assertEquals(201,request.getJSONObject("param").getInt("dirId"))
        assertEquals(202084654L,request.getJSONObject("param").getJSONArray("v_songInfo").getJSONObject(0).getLong("songId"))
        assertNotEquals(song.id,202084654L)
        retCode=null
        try {repo.setLiked(song,false);fail("Missing acknowledgement must fail")}
        catch(e:QQMusicException){assertEquals(QQMusicException.Kind.REQUEST_FAILED,e.kind)}
        Mockito.verify(accounts,Mockito.times(2)).notifyLibraryChanged()
    }
}
