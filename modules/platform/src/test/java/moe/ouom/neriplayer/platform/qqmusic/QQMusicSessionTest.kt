package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.*
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QQMusicSessionTest {
    private val old = "musicid=123; musickey=old"
    private val fresh = "musicid=123; musickey=fresh"

    @Test fun `QR credentials retain renewal fields and canonical aliases`() {
        val result = parseQQCookies(qqSessionCookie(JSONObject("""{
            "musicid":123,"musickey":"new","refresh_key":"renew","refresh_token":"token",
            "openid":"open","access_token":"access","expired_at":456,
            "musickeyCreateTime":1000,"keyExpiresIn":259200,"loginType":2
        }""")))
        assertEquals("renew", result["refresh_key"])
        assertEquals("token", result["refresh_token"])
        assertEquals("259200", result["keyExpiresIn"])
        assertEquals("1000", result["musickeyCreateTime"])
        assertEquals("123", result["str_musicid"])
        listOf("musickey", "qqmusic_key", "qm_keyst", "authst").forEach { assertEquals("new", result[it]) }
    }

    @Test fun `renewal cannot switch accounts or accept injected cookies`() {
        listOf(
            JSONObject("""{"musicid":456,"musickey":"new"}"""),
            JSONObject().put("musicid",123).put("musickey","new; musicid=456"),
            JSONObject().put("musicid",123).put("musickey","new").put("refresh_key","bad\r\nheader")
        ).forEach { data ->
            try { qqSessionCookie(data, old, "123"); fail("Unsafe renewal must be rejected") }
            catch (_: QQMusicException) { }
        }
        val merged = qqSessionCookie(JSONObject("""{"musickey":"fresh"}"""), "$old; refresh_key=retain", "123")
        assertEquals("123", qqAccountId(merged))
        assertEquals("retain", parseQQCookies(merged)["refresh_key"])
    }

    @Test fun `renewal uses server expiry rather than a fixed three days`() {
        val cookie = "$old; musickeyCreateTime=1000; keyExpiresIn=7200"
        assertFalse(qqSessionNeedsRefresh(cookie,7599))
        assertTrue(qqSessionNeedsRefresh(cookie,7600))
        assertFalse(qqSessionNeedsRefresh(old,999999))
        assertFalse(qqSessionNeedsRefresh("$old; musickeyCreateTime=1; keyExpiresIn=-1",999999))
    }

    @Test fun `concurrent rejections renew once and reuse the persisted credential`() = runBlocking {
        var stored = old
        var calls = 0
        val session = QQMusicSession({stored}, {expected,updated ->
            if(stored==expected) { stored=updated; true } else false
        }, { calls++; yield(); fresh })
        val results = coroutineScope { List(8) { async { session.cookie(old) } }.awaitAll() }
        assertEquals(1,calls)
        assertTrue(results.all { it == fresh })
        assertEquals(fresh,stored)
    }

    @Test fun `logout during renewal never restores the old session`() = runBlocking {
        var stored = old
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val session = QQMusicSession({stored}, {expected,updated ->
            if(stored==expected) { stored=updated; true } else false
        }, { started.complete(Unit); finish.await(); fresh })
        val request = async { session.cookie(old) }
        started.await()
        stored = ""
        finish.complete(Unit)
        assertEquals("",request.await())
        assertEquals("",stored)
    }

    @Test fun `failed renewals are throttled without clearing credentials`() = runBlocking {
        var calls = 0
        var time = 1_000_000L
        val session = QQMusicSession({old}, {_,_ -> fail("Must not overwrite storage"); false},
            { calls++; throw java.io.IOException("offline") }, {time})
        repeat(5) { assertEquals(old,session.cookie(old)) }
        assertEquals(1,calls)
        time += 300_000
        assertEquals(old,session.cookie(old))
        assertEquals(2,calls)
    }

    @Test fun `revoked refresh returns a login error only after the playback credential was rejected`() = runBlocking {
        val expired = "$old; musickeyCreateTime=1000; keyExpiresIn=2000"
        var calls = 0
        fun session() = QQMusicSession({expired}, {_,_ -> false}, {
            calls++; throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED,"301")
        }, {10_000_000L})
        assertEquals(expired,session().cookie())
        try { session().cookie(expired); fail("Must request reauthorization") }
        catch (error: QQMusicException) { assertEquals(QQMusicException.Kind.LOGIN_REQUIRED,error.kind) }
        assertEquals(2,calls)
    }

    @Test fun `proactive revocation is reused when playback subsequently rejects the same key`() = runBlocking {
        val expired = "$old; musickeyCreateTime=1000; keyExpiresIn=2000"
        var calls = 0
        val session = QQMusicSession({expired}, {_,_ -> false}, {
            calls++; throw QQMusicException(QQMusicException.Kind.LOGIN_REQUIRED,"301")
        }, {10_000_000L})
        assertEquals(expired,session.cookie())
        try { session.cookie(expired); fail("Must request reauthorization without a second renewal") }
        catch (error: QQMusicException) { assertEquals(QQMusicException.Kind.LOGIN_REQUIRED,error.kind) }
        assertEquals(1,calls)
    }

    @Test fun `ordinary cookie access renews an expiring key once`() = runBlocking {
        var stored = "$old; musickeyCreateTime=1000; keyExpiresIn=7200"
        var calls = 0
        val session = QQMusicSession({stored}, {expected,updated ->
            if(stored==expected) { stored=updated; true } else false
        }, { calls++; "$fresh; musickeyCreateTime=7600; keyExpiresIn=7200" }, {7_600_000L})
        assertEquals("fresh",qqMusicKey(session.cookie()))
        assertEquals("fresh",qqMusicKey(session.cookie()))
        assertEquals(1,calls)
    }

    @Test fun `cancellation is propagated without saving a credential`() = runBlocking {
        val session = QQMusicSession({old}, {_,_ -> fail("Cancelled renewal must not save"); false},
            { throw CancellationException("cancelled") })
        try { session.cookie(old); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
