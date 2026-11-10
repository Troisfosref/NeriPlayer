package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicBrowseTest {
    @Test fun `playlist directory is distinct from its public ID`() {
        val p=requireNotNull(qqBrowsePlaylist(JSONObject("""{"tid":"123456","dirid":42,"title":"Mine","cover":{"medium_url":"//img.qq.com/a.jpg"},"song_cnt":130}""")))
        assertEquals("123456",p.id);assertEquals(42,p.directory);assertEquals(130,p.count)
        assertEquals("https://img.qq.com/a.jpg",p.cover)
        assertEquals("dir:42",qqBrowsePlaylist(JSONObject("""{"dirid":42,"title":"Private"}"""))?.id)
    }
    @Test fun `track pages use raw positions and preserve media identities`() {
        val data=JSONObject("""{"songlist":[{"track":{"mid":"catalog","file":{"media_mid":"file"},"title":"Song"}},{"unavailable":true}],"total_song_num":3}""")
        val page=qqBrowseTrackPage(data,0,"songlist",3)
        assertEquals(2,page.nextOffset);assertEquals("catalog",page.songs.single().audioId)
        assertEquals("file",page.songs.single().subAudioId)
        assertNull(qqBrowseTrackPage(JSONObject("""{"songlist":[]}"""),2,"songlist",3).nextOffset)
    }
    @Test fun `legacy packed directory songs and radio wrappers map to QQ songs`() {
        val songs=qqBrowseSongs(JSONArray("""[{"data":"mid|Title|0|Artist|albumMid|Album|0|120"},{"Track":{"mid":"radio","title":"Radio"}}]"""))
        assertEquals(listOf("mid","radio"),songs.map { it.audioId })
        assertEquals(120000L,songs.first().durationMs);assertEquals("Artist",songs.first().artist)
    }
    @Test fun `favorites request uses directory 201 with web comm and pages by offset`() = runBlocking {
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        var requestBody: JSONObject?=null
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val b=Buffer();requireNotNull(chain.request().body).writeTo(b);requestBody=JSONObject(b.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"playlist":{"code":0,"data":{"songlist":[],"total_song_num":100}}}""".toResponseBody()).build()
        }.build()
        QQMusicBrowseRepository(accounts,QQMusicClient(http)).tracks(QQMusicPlaylist("favorites","Liked",directory=201),100)
        val body=requireNotNull(requestBody)
        assertEquals(19,body.getJSONObject("comm").getInt("ct"));assertFalse(body.getJSONObject("comm").has("v"))
        val params=body.getJSONObject("playlist").getJSONObject("param")
        assertEquals(201,params.getInt("dirid"));assertEquals(0L,params.getLong("disstid"));assertEquals(100,params.getInt("song_begin"))
    }
    @Test fun `exactly one hundred created playlists finish on an empty following page`() = runBlocking {
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=123; musickey=fixture")
        val offsets=mutableListOf<String?>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val offset=chain.request().url.queryParameter("sin");offsets+=offset
            val list=JSONArray()
            if(offset=="0") repeat(100) { list.put(JSONObject().put("dissid",1000+it).put("diss_name","Playlist $it")) }
            val response=JSONObject().put("code",0).put("data",JSONObject().put("disslist",list))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()
        val result=QQMusicBrowseRepository(accounts,QQMusicClient(http)).created()
        assertEquals(100,result.size);assertEquals(listOf("0","100"),offsets)
    }

}
