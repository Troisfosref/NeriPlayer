package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicAlbumCountRepository
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class QQMusicAlbumCountTest {
    @Test fun `a zero song total cannot be accepted when the response contains tracks`() = runBlocking {
        for(page in listOf("""{"totalNum":0,"songList":[{}]}""","""{"songList":[]}""")) {
            try {countPage(page);fail("Inconsistent or missing metadata must remain unknown")}
            catch(_: IllegalArgumentException) { }
        }
        assertEquals(0,countPage("""{"totalNum":0,"songList":[]}"""))
    }
    @Test fun `album count comes from the same authenticated song endpoint as album detail`() = runBlocking {
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=42; musickey=fixture")
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=Buffer();chain.request().body!!.writeTo(buffer)
            val root=JSONObject(buffer.readUtf8())
            val request=root.getJSONObject("album_count")
            assertEquals("music.musichallAlbum.AlbumSongList",request.getString("module"))
            assertEquals("GetAlbumSongList",request.getString("method"))
            val params=request.getJSONObject("param")
            assertEquals("albumMid",params.getString("albumMid"))
            assertEquals(0,params.getInt("begin"));assertEquals(100,params.getInt("num"));assertEquals(2,params.getInt("order"))
            assertEquals(19,root.getJSONObject("comm").getInt("ct"))
            assertEquals(0,root.getJSONObject("comm").getInt("cv"))
            assertEquals("42",root.getJSONObject("comm").getString("uin"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"album_count":{"code":0,"data":{"totalNum":12,"songList":[{},{}]}}}""".toResponseBody()).build()
        }.build()
        assertEquals(12,QQMusicAlbumCountRepository(accounts,QQMusicClient(http)).count("albumMid"))
    }

    @Test fun `simultaneous requests share one lookup and completed counts are cached`() = runTest {
        var calls=0
        val gate=CompletableDeferred<Unit>()
        val repo=QQMusicAlbumCountRepository({calls++;gate.await();9})
        val first=async {repo.count("mid")};val second=async {repo.count("mid")}
        runCurrent();assertEquals(1,calls)
        gate.complete(Unit)
        assertEquals(listOf(9,9),awaitAll(first,second))
        assertEquals(9,repo.count("mid"));assertEquals(1,calls)
    }

    @Test fun `background album lookups have at most three network requests in flight`() = runTest {
        var active=0;var maximum=0
        val gate=CompletableDeferred<Unit>()
        val repo=QQMusicAlbumCountRepository({
            active++;maximum=maxOf(maximum,active)
            try {gate.await();12} finally {active--}
        })
        val requests=(1..10).map { i ->async {repo.count("mid$i")} }
        runCurrent();assertEquals(3,maximum)
        gate.complete(Unit);requests.awaitAll()
        assertTrue(maximum<=3)
    }

    @Test fun `failed or cancelled lookups are not stored as a zero count`() = runTest {
        var calls=0
        val repo=QQMusicAlbumCountRepository({if(++calls==1) error("offline") else 15})
        try {repo.count("mid");fail("Must fail without a count")} catch(_: IllegalStateException) { }
        assertEquals(15,repo.count("mid"));assertEquals(2,calls)
        var cancellations=0
        val cancellable=QQMusicAlbumCountRepository({if(++cancellations==1) awaitCancellation() else 7})
        val job=launch {cancellable.count("mid")};runCurrent();job.cancelAndJoin()
        assertEquals(7,cancellable.count("mid"))
    }

    @Test fun `negative counts are rejected and fresh counts expire after five minutes`() = runTest {
        var clock=1_000L;var calls=0
        val repo=QQMusicAlbumCountRepository({++calls},{clock})
        assertEquals(1,repo.count("mid"));assertEquals(1,repo.count("mid"))
        clock+=300_001L;assertEquals(2,repo.count("mid"))
        try {QQMusicAlbumCountRepository({-1}).count("mid");fail("Unknown must not become a cached count")}
        catch(_: IllegalArgumentException) { }
    }

    @Test fun `cancelling one screen does not cancel another caller waiting for the same album`() = runTest {
        var calls=0
        val repo=QQMusicAlbumCountRepository({if(++calls==1) awaitCancellation() else 12})
        val first=launch {repo.count("mid")};runCurrent()
        val second=async {repo.count("mid")};runCurrent()
        first.cancelAndJoin()
        assertEquals(12,second.await());assertEquals(2,calls)
    }

    private suspend fun countPage(page: String): Int {
        val accounts=Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("")
        val client=QQMusicClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"album_count":{"code":0,"data":$page}}""".toResponseBody()).build()
        }.build())
        return QQMusicAlbumCountRepository(accounts,client).count("mid")
    }
}
