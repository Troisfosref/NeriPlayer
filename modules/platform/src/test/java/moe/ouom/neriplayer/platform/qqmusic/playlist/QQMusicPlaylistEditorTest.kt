package moe.ouom.neriplayer.platform.qqmusic.playlist

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.platform.qqmusic.*
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAccountRepository
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QQMusicPlaylistEditorTest {
    @Test fun `picker lists own writable directories and likes but never collected or system folders`() = runBlocking {
        val f = Fixture()
        f.created = """[{"tid":"123456","dirid":42,"title":"Mine"},
            {"tid":"333","dirid":201,"title":"我喜欢的音乐"},
            {"tid":"444","dirid":202,"title":"QZone背景音乐"},
            {"tid":"555","title":"Unknown directory"}]"""
        assertEquals(listOf("favorites", "123456"), f.editor.writablePlaylists().map { it.id })
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `created playlist writes use server directory and catalog number with deduplicated song identities`() = runBlocking {
        val f = Fixture()
        val song = f.song(7)
        val result = f.editor.update("123456", listOf(song, song.copy(name = "Other title")), true)
        assertNull(result.error)
        assertEquals(1, result.total)
        assertEquals(listOf(song), result.completed)
        val req = f.writes.single()
        assertEquals("AddSonglist", req.getString("method"))
        val p = req.getJSONObject("param")
        assertEquals(42, p.getInt("dirId"))
        assertEquals("Mine", p.getString("dirName"))
        assertEquals(123456L, p.getLong("tid"))
        assertEquals(1007L, p.getJSONArray("v_songInfo").getJSONObject(0).getLong("songId"))
        assertNotEquals(song.id, 1007L)
        assertEquals(listOf("123456"), f.invalidated)
        Mockito.verify(f.accounts).notifyLibraryChanged()
    }

    @Test fun `arbitrary public and formerly owned playlists cannot be mutated`() = runBlocking {
        val f = Fixture()
        for (id in listOf("foreign", "999999", "album:albumMid", "dir:999", "daily")) {
            try { f.editor.update(id, listOf(f.song(1)), false); fail("Not owned") }
            catch (_: QQMusicException) { }
        }
        assertTrue(f.writes.isEmpty())
        assertTrue(f.invalidated.isEmpty())
        Mockito.verify(f.accounts, Mockito.never()).notifyLibraryChanged()
    }

    @Test fun `batch removal stops at rejected chunk and returns confirmed successes without automatic retry`() = runBlocking {
        val f = Fixture()
        f.writeReply = { index -> if (index == 2) JSONObject().put("retCode", 80092) else JSONObject().put("retCode", 0) }
        val result = f.editor.update("favorites", (1..205).map(f::song), false)
        assertEquals(205, result.total)
        assertEquals(100, result.completed.size)
        assertNotNull(result.error)
        assertEquals(2, f.writes.size)
        f.writes.forEach {
            assertEquals("DelSonglist", it.getString("method"))
            assertEquals(201, it.getJSONObject("param").getInt("dirId"))
            assertEquals(0L, it.getJSONObject("param").getLong("tid"))
        }
        assertEquals(listOf("favorites"), f.invalidated)
    }

    @Test fun `complete batch includes its final short chunk`() = runBlocking {
        val f = Fixture()
        val result = f.editor.update("123456", (1..205).map(f::song), true)
        assertNull(result.error)
        assertEquals(205, result.completed.size)
        assertEquals(listOf(100, 100, 5), f.writes.map {
            it.getJSONObject("param").getJSONArray("v_songInfo").length()
        })
        assertEquals(listOf("123456"), f.invalidated)
    }

    @Test fun `missing acknowledgement is never reported as success and forces refresh`() = runBlocking {
        val f = Fixture()
        f.writeReply = { JSONObject() }
        val result = f.editor.update("favorites", listOf(f.song(1)), true)
        assertNotNull(result.error)
        assertTrue(result.completed.isEmpty())
        assertEquals(listOf("favorites"), f.invalidated)
    }

    @Test fun `business envelope errors are rejected even with inner retCode zero`() = runBlocking {
        val f = Fixture()
        f.writeCode = 80105
        assertNotNull(f.editor.update("favorites", listOf(f.song(1)), true).error)
        assertEquals(listOf("favorites"), f.invalidated)
    }

    @Test fun `failed catalog resolution prevents any partial writes`() = runBlocking {
        val f = Fixture()
        f.badDetail = 2
        try { f.editor.update("123456", listOf(f.song(1), f.song(2)), true); fail("Missing catalog ID") }
        catch (_: QQMusicException) { }
        assertTrue(f.writes.isEmpty())
        assertTrue(f.invalidated.isEmpty())
    }

    @Test fun `account switching while resolving songs prevents writes with stale credentials`() = runBlocking {
        val f = Fixture()
        f.afterDetail = { f.cookie = "musicid=999; musickey=other" }
        try { f.editor.update("favorites", listOf(f.song(1)), true); fail("Account changed") }
        catch (e: QQMusicException) { assertEquals(QQMusicException.Kind.LOGIN_REQUIRED, e.kind) }
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `cancellation after dispatch still invalidates ambiguous remote state`() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.writeReply = {
            entered.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            JSONObject().put("retCode", 0)
        }
        val job = launch { f.editor.update("favorites", listOf(f.song(1)), true) }
        try {
            withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
        } finally { release.countDown() }
        assertEquals(listOf("favorites"), f.invalidated)
        Mockito.verify(f.accounts).notifyLibraryChanged()
    }

    @Test fun `ordinary read type one removes the actual song using write type zero`() = runBlocking {
        val f = Fixture()
        val result = f.editor.update("123456", listOf(f.song(7)), false)
        assertNull(result.error)
        assertEquals(listOf(f.song(7)), result.completed)
        assertFalse(7 in f.members)
        val info = f.writes.single().getJSONObject("param").getJSONArray("v_songInfo").getJSONObject(0)
        assertEquals(1007L, info.getLong("songId"))
        assertEquals(0, info.getInt("songType"))
        assertTrue(f.membershipReads > 0)
    }

    @Test fun `acknowledged deletion that leaves songs behind is never reported as success`() = runBlocking {
        val f = Fixture()
        f.keepOnDelete = setOf(1)
        val result = f.editor.update("favorites", listOf(f.song(1), f.song(2)), false)
        assertNotNull(result.error)
        assertEquals(listOf(f.song(2)), result.completed)
        assertEquals(2, result.total)
        assertTrue(1 in f.members)
        assertFalse(2 in f.members)
        assertEquals(1, f.writes.size) // Verification retries reads, never repeats writes.
        assertEquals(listOf("favorites"), f.invalidated)
    }

    @Test fun `batch removal verifies every chunk including final short chunk`() = runBlocking {
        val f = Fixture()
        val result = f.editor.update("favorites", (1..205).map(f::song), false)
        assertNull(result.error)
        assertEquals(205, result.completed.size)
        assertTrue(f.members.isEmpty())
        assertEquals(listOf(100, 100, 5), f.writes.map {
            it.getJSONObject("param").getJSONArray("v_songInfo").length()
        })
    }

    @Test fun `picker checks complete membership beyond page one and disables only fully present selection`() = runBlocking {
        val f = Fixture()
        val target = QQMusicPlaylist("123456", "Mine", directory = 42)
        assertTrue(f.editor.containsSelection(target, listOf(f.song(201))))
        assertTrue(f.membershipReads >= 3)
        assertFalse(f.editor.containsSelection(target, listOf(f.song(201), f.song(206))))
        assertTrue(f.editor.containsSelection(target, listOf(f.song(1), f.song(201))))
        assertFalse(f.editor.containsSelection(target, emptyList()))
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `failed membership lookup does not turn an unknown playlist into an available target`() = runBlocking {
        val f = Fixture()
        f.membershipCode = 500
        try { f.editor.containsSelection(QQMusicPlaylist("favorites", "Liked"), listOf(f.song(1))); fail("Unknown membership") }
        catch (_: QQMusicException) { }
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `write types follow the client mapping including special types`() {
        val expected = mapOf(0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 1, 5 to 5,
            6 to 1, 110 to 1, 111 to 111, 112 to 112, 113 to 113, 114 to 1)
        expected.forEach { (read, write) -> assertEquals(write, qqMusicPlaylistWriteType(read)) }
    }

    @Test fun `cache storage failure does not hide an acknowledged cloud change`() = runBlocking {
        val f = Fixture()
        val editor = QQMusicPlaylistEditor(f.accounts, f.client) { error("Storage unavailable") }
        assertNull(editor.update("favorites", listOf(f.song(1)), true).error)
        Mockito.verify(f.accounts).notifyLibraryChanged()
    }

    private class Fixture {
        val accounts = Mockito.mock(QQMusicAccountRepository::class.java)
        var cookie = "musicid=123; musickey=fixture"
        var created = """[{"tid":"123456","dirid":42,"title":"Mine"}]"""
        var badDetail: Int? = null
        var afterDetail: () -> Unit = {}
        var writeReply: (Int) -> JSONObject = { JSONObject().put("retCode", 0) }
        var writeCode = 0
        val members = (1..205).toMutableSet()
        var keepOnDelete = emptySet<Int>()
        var membershipReads = 0
        var membershipCode = 0
        val writes = mutableListOf<JSONObject>()
        val invalidated = mutableListOf<String>()
        init { Mockito.`when`(runBlocking { accounts.cookie() }).thenAnswer { cookie } }
        val client = QQMusicClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val root = if (request.method == "GET") {
                assertTrue(request.url.encodedPath.endsWith("fcg_user_created_diss"))
                JSONObject().put("code", 0).put("data", JSONObject().put("disslist", JSONArray(created)))
            } else {
                val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                if (body.has("songinfo")) {
                    val mid = body.getJSONObject("songinfo").getJSONObject("param").getString("song_mid")
                    val index = mid.removePrefix("song").toInt()
                    afterDetail()
                    envelope("songinfo", JSONObject().put("track_info", JSONObject()
                        .put("id", if (badDetail == index) 0 else 1000 + index).put("type", 1)))
                } else if (body.has("playlist")) {
                    membershipReads++
                    val param = body.getJSONObject("playlist").getJSONObject("param")
                    val offset = param.getInt("song_begin")
                    val raw = JSONArray(members.sorted().drop(offset).take(100).map { index ->
                        JSONObject().put("mid", "song$index").put("type", 1).put("id", 1000 + index)
                    })
                    envelope("playlist", JSONObject().put("songlist", raw).put("total_song_num", members.size), membershipCode)
                } else {
                    val requestBody = body.getJSONObject("req_0")
                    writes += requestBody
                    val reply = writeReply(writes.size)
                    if (writeCode == 0 && reply.optInt("retCode", -1) == 0 && requestBody.getString("method") == "DelSonglist") {
                        val info = requestBody.getJSONObject("param").getJSONArray("v_songInfo")
                        for (i in 0 until info.length()) {
                            val item = info.getJSONObject(i)
                            val index = item.getInt("songId") - 1000
                            // Mirrors QQ's read type 1 / write type 0 mismatch: wrong type is an acknowledged no-op.
                            if (item.getInt("songType") == 0 && index !in keepOnDelete) members.remove(index)
                        }
                    }
                    envelope("req_0", reply, writeCode)
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(root.toString().toResponseBody()).build()
        }.build())
        val editor = QQMusicPlaylistEditor(accounts, client) { invalidated += it.id }
        fun song(index: Int) = requireNotNull(mapQQMusicSong(JSONObject().put("mid", "song$index").put("title", "Song $index")))
        private fun envelope(key: String, data: JSONObject, code: Int = 0) = JSONObject().put("code", 0)
            .put(key, JSONObject().put("code", code).put("data", data))
    }
}
