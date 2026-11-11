package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.artist.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import moe.ouom.neriplayer.platform.qqmusic.cache.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicArtistTest {
    @Test fun `song navigation uses real album and all distinct singer MIDs`() {
        val result=parseQQMusicSongCollections(JSONObject("""{"album":{"mid":"albumMid","id":42,"name":"Album"},"singer":[{"mid":"first","name":"A"},{"mid":"second","name":"B"},{"mid":"first","name":"duplicate"},{"id":99,"name":"missing MID"},{"mid":"0","name":"invalid"}]}"""))
        assertEquals("album:albumMid",result.album?.id)
        assertEquals(listOf("artist:first","artist:second"),result.artists.map { it.id })
        assertNull(parseQQMusicSongCollections(JSONObject("""{"album":{"id":42,"name":"No MID"}}""")).album)
    }

    @Test fun `navigation looks up catalog MID once and reuses cached metadata offline`() = runBlocking {
        var calls=0
        val repository=QQMusicSongNavigationRepository(accounts(),client { body ->
            calls++
            assertEquals("catalog",body.getJSONObject("songinfo").getJSONObject("param").getString("song_mid"))
            JSONObject().put("songinfo", envelope(JSONObject().put("track_info",JSONObject().put("album",
                JSONObject().put("mid","albumMid").put("name","Album")))))
        })
        assertEquals(repository.resolve(song()),repository.resolve(song(),allowNetwork=false))
        assertEquals(1,calls)
    }

    @Test fun `artist requests keep personalized homepage comm and chronological album order`() = runBlocking {
        val requests=java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val repository=QQMusicArtistRepository(accounts(),client { body ->
            requests+=body
            val method=body.getJSONObject("artist").getString("method")
            val data=when(method) {
                "GetHomepageHeader" -> JSONObject("""{"Info":{"Singer":{"Name":"Aimer"},"BaseInfo":{"Avatar":"http://example.invalid/avatar"}}}""")
                "GetHomepageTabDetail" -> JSONObject("""{"IntroductionTab":{"List":[{"SingerInfoList":[{"Content":"Biography"}]}]}}""")
                "GetAlbumList" -> JSONObject("""{"total":2,"albumList":[{"albumInfo":{"albumMid":"albumMid","albumName":"Album","songNum":9}}]}""")
                else -> JSONObject("""{"totalNum":2,"songList":[{"songInfo":{"mid":"catalog","title":"Track","interval":12}}]}""")
            }
            JSONObject().put("artist",envelope(data))
        })
        val artist=QQMusicPlaylist("artist:singerMid","fallback")
        val profile=repository.profile(artist)
        assertEquals("Aimer",profile.name);assertEquals("Biography",profile.introduction)
        assertEquals("https://example.invalid/avatar",profile.avatar)
        val albums=repository.albums("singerMid",0)
        assertEquals("album:albumMid",albums.items.single().id)
        assertEquals(9,albums.items.single().count);assertEquals(1,albums.nextOffset)
        val songs=repository.songs("singerMid",0)
        assertEquals("catalog",songs.items.single().audioId);assertEquals(1,songs.nextOffset)
        for(body in requests) {
            val request=body.getJSONObject("artist");val method=request.getString("method")
            val comm=body.getJSONObject("comm");val params=request.getJSONObject("param")
            if(method.startsWith("GetHomepage")) {
                assertEquals(11,comm.getInt("ct"));assertEquals(20080000,comm.getInt("cv"))
                assertEquals("singerMid",params.getString("SingerMid"))
            } else {
                assertEquals(19,comm.getInt("ct"));assertEquals(0,comm.getInt("cv"))
                assertEquals("singerMid",params.getString("singerMid"))
                assertEquals(if(method=="GetAlbumList") 0 else 1,params.getInt("order"))
            }
        }
    }

    @Test fun `artist pagination uses raw positions even when some albums are unsupported`() = runBlocking {
        val repository=QQMusicArtistRepository(accounts(),client {
            JSONObject().put("artist",envelope(JSONObject().put("total",100).put("albumList",JSONArray().apply {
                repeat(50) { put(JSONObject().put("albumName","missing MID")) }
            })))
        })
        val page=repository.albums("singerMid",0)
        assertTrue(page.items.isEmpty());assertEquals(50,page.nextOffset)
    }

    @Test fun `optional biography failure retains header but cancellation propagates`() = runBlocking {
        val account=accounts()
        val repository=QQMusicArtistRepository(account,client { body ->
            if(body.getJSONObject("artist").getString("method")=="GetHomepageTabDetail") {
                return@client JSONObject().put("artist",JSONObject().put("code",1))
            }
            JSONObject().put("artist",envelope(JSONObject("""{"Info":{"Singer":{"Name":"Artist"}}}""")))
        })
        assertEquals("",repository.profile(QQMusicPlaylist("artist:mid","Artist")).introduction)
        Mockito.`when`(account.cookie()).thenThrow(CancellationException("cancelled"))
        try { repository.profile(QQMusicPlaylist("artist:mid","Artist"));fail("Must propagate cancellation") }
        catch(_: CancellationException) { }
    }

    @Test fun `artist cache preserves both cursors full biography album MID and playback identity`() {
        val snapshot=QQMusicArtistSnapshot(QQMusicArtistProfile("mid","Artist",null,null,introduction="Full biography\nMore"),
            QQMusicArtistPage(listOf(song()),50,100),QQMusicArtistPage(listOf(QQMusicPlaylist("album:albumMid","Album",count=9)),50,80))
        val record=snapshot.artistCacheRecord(500L)
        assertEquals("qqmusic",record.platform)
        assertEquals(snapshot,record.artistSnapshot())
        assertEquals("0:artist:mid",record.cacheKey)
    }

    private suspend fun accounts()=Mockito.mock(QQMusicAccountRepository::class.java).also {
        Mockito.`when`(it.cookie()).thenReturn("musicid=42; musickey=fixture")
    }
    private fun envelope(data: JSONObject)=JSONObject().put("code",0).put("data",data)
    private fun client(reply: (JSONObject) -> JSONObject)=QQMusicClient(OkHttpClient.Builder().addInterceptor { chain ->
        val buffer=Buffer();chain.request().body!!.writeTo(buffer)
        val root=reply(JSONObject(buffer.readUtf8())).put("code",0)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(root.toString().toResponseBody()).build()
    }.build())
    private fun song(): SongItem {
        val ref=QQMusicSongRef("catalog","media",2)
        return SongItem(ref.numericId,"Track","Artist","Album",0L,12_000L,null,
            mediaUri=ref.mediaUri,channelId=QQMusicSongRef.CHANNEL,audioId=ref.mid,subAudioId=ref.mediaMid)
    }
}
