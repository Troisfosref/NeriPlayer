package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
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

class QQMusicCatalogTest {
    @Test fun `four categories use MusicOne desktop contract and raw page positions`() = runBlocking {
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=42; musickey=fixture")
        val requests = mutableListOf<JSONObject>()
        val repository = QQMusicCatalogRepository(accounts, client { body ->
            requests += body
            """{"code":0,"search":{"code":0,"data":{"body":{},"meta":{"sum":0,"nextpage":-1}}}}"""
        })
        for (type in QQMusicSearchType.entries) {
            assertFalse(repository.search(" demo ", type, 2).hasMore)
            val request = requests.last()
            val search = request.getJSONObject("search")
            assertEquals("music.search.SearchCgiService", search.getString("module"))
            assertEquals("DoSearchForQQMusicDesktop", search.getString("method"))
            val params = search.getJSONObject("param")
            assertEquals(type.apiType, params.getInt("search_type"))
            assertEquals(2, params.getInt("page_num"))
            assertEquals(30, params.getInt("sin"))
            assertEquals(60, params.getInt("ein"))
            assertEquals("demo", params.getString("query"))
            assertEquals(19, request.getJSONObject("comm").getInt("ct"))
            assertEquals(1859, request.getJSONObject("comm").getInt("cv"))
            assertEquals("42", request.getJSONObject("comm").getString("uin"))
        }
    }

    @Test fun `albums and artists retain MIDs and distinct native detail routes`() {
        val album = parseQQCatalogPage(JSONObject("""{"body":{"album":{"list":[{"albumMID":"albumMid","name":"A &amp; B","singerName":"Singer","song_count":12}]}},"meta":{"nextpage":-1}}"""), QQMusicSearchType.ALBUM, 1)
            .collections.single()
        assertEquals("A & B", album.title)
        assertEquals("Singer", album.subtitle)
        assertEquals(12, album.count)
        assertEquals("album:albumMid", album.playlist().id)
        assertTrue(album.cover!!.contains("T002R500x500M000albumMid"))
        val artist = parseQQCatalogPage(JSONObject("""{"body":{"singer":[{"singerMID":"artistMid","singerName":"<em>Artist</em>"},{"singerMID":"artistMid","singerName":"Duplicate"},{"singerID":123,"singerName":"Missing MID"}]},"meta":{"sum":3}}"""), QQMusicSearchType.ARTIST, 1)
            .collections.single()
        assertEquals("Artist", artist.title)
        assertEquals("artist:artistMid", artist.playlist().id)
        assertTrue(artist.cover!!.contains("T001R300x300M000artistMid"))
    }

    @Test fun `playlist items use public dissid and paging uses raw metadata`() {
        val result = parseQQCatalogPage(JSONObject("""{"body":{"songlist":{"items":[{"dissid":"12345","dissname":"List","nickname":"Creator","songnum":4,"imgurl":"//example.invalid/cover.jpg"},{}]}},"meta":{"sum":65}}"""), QQMusicSearchType.PLAYLIST, 2)
        assertTrue(result.hasMore)
        assertEquals("12345", result.collections.single().playlist().id)
        assertEquals("https://example.invalid/cover.jpg", result.collections.single().cover)
        assertEquals("Creator", result.collections.single().subtitle)
    }

    @Test fun `song search preserves catalog and media identities`() {
        val page = parseQQCatalogPage(JSONObject("""{"body":{"song":{"list":[{"mid":"songMid","title":"Track","file":{"media_mid":"fileMid"}}]}},"meta":{"nextpage":-1}}"""), QQMusicSearchType.SONG, 1)
        assertEquals("songMid", page.songs.single().audioId)
        assertEquals("fileMid", page.songs.single().subAudioId)
        assertFalse(page.hasMore)
        assertFalse(parseQQCatalogPage(JSONObject("""{"body":{"song":[]},"meta":{"nextpage":1}}"""), QQMusicSearchType.SONG, 1).hasMore)
    }

    @Test fun `restricted or malformed responses remain errors instead of empty results`() {
        for (data in listOf("""{"body":{},"meta":{"is_filter":-12}}""", """{"code":2001,"body":{}}""", "{}")) {
            try {
                parseQQCatalogPage(JSONObject(data), QQMusicSearchType.ALBUM, 1)
                fail("Invalid response must fail")
            } catch (_: QQMusicException) { }
        }
    }

    @Test fun `desktop song rejection retains legacy endpoint but collection errors propagate`() = runBlocking {
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("musicid=42; musickey=fixture")
        var calls = 0
        val repository = QQMusicCatalogRepository(accounts, client { """{"code":0,"search":{"code":2001}}""" }) { _, _ ->
            calls++; QQMusicSearchPage(emptyList(), false)
        }
        repository.search("demo", QQMusicSearchType.SONG, 1)
        assertEquals(1, calls)
        try { repository.search("demo", QQMusicSearchType.PLAYLIST, 1); fail("Must propagate") }
        catch (_: QQMusicException) { }
        assertEquals(1, calls)
    }

    @Test fun `cancellation never starts fallback or another account request`() = runBlocking {
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenThrow(CancellationException("cancelled"))
        val repository = QQMusicCatalogRepository(accounts, client { error("No network expected") }) { _, _ ->
            error("Cancellation must not fallback")
        }
        try { repository.search("demo", QQMusicSearchType.SONG, 1); fail("Must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun `album and artist details call their own endpoints and preserve raw offsets`() = runBlocking {
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        Mockito.`when`(accounts.cookie()).thenReturn("")
        val requests = mutableListOf<JSONObject>()
        val repository = QQMusicCatalogRepository(accounts, client { body ->
            requests += body
            """{"code":0,"collection":{"code":0,"data":{"songList":[{"songInfo":{"mid":"track","title":"Track"}},{}],"totalNum":65}}}"""
        })
        val album = repository.collectionTracks("album:albumMid", 0)
        assertEquals(2, album.nextOffset)
        assertEquals("track", album.songs.single().audioId)
        val albumRequest = requests.last().getJSONObject("collection")
        assertEquals("GetAlbumSongList", albumRequest.getString("method"))
        assertEquals(2, albumRequest.getJSONObject("param").getInt("order"))
        repository.collectionTracks("artist:artistMid", 2)
        val artistRequest = requests.last().getJSONObject("collection")
        assertEquals("GetSingerSongList", artistRequest.getString("method"))
        assertEquals(2, artistRequest.getJSONObject("param").getInt("begin"))
        assertEquals(1, artistRequest.getJSONObject("param").getInt("order"))
    }

    private fun client(response: (JSONObject) -> String): QQMusicClient = QQMusicClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            requireNotNull(chain.request().body).writeTo(buffer)
            val body = response(JSONObject(buffer.readUtf8()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
    )
}
