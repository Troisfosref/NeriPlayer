package moe.ouom.neriplayer.platform.qqmusic

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.parseQQCookies
import moe.ouom.neriplayer.platform.qqmusic.api.qqHash
import moe.ouom.neriplayer.platform.qqmusic.auth.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QQMusicQrLoginTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    @Before fun initializeAndroidHttp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test fun `confirmed QR keeps graph ticket when QQ clears the parent domain cookie`() = runBlocking {
        val calls = mutableListOf<Request>()
        val jump = "https://graph.qq.com/oauth2.0/login_jump?auth=fixture"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            val body: ByteArray = when (calls.size) {
                1 -> {
                    response.addHeader("Set-Cookie", "qrsig=qr-session; Domain=ptlogin2.qq.com; Path=/; Secure")
                    png
                }
                2 -> {
                    assertEquals("qrsig=qr-session", request.header("Cookie"))
                    "ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?ptsigx=fixture','0','ok','');".toByteArray()
                }
                3 -> {
                    response.code(302).addHeader("Location", jump)
                        .addHeader("Set-Cookie", "p_skey=valid-graph-ticket; Domain=graph.qq.com; Path=/; Secure")
                        .addHeader("Set-Cookie", "p_uin=o0000000456; Domain=qq.com; Path=/; Secure")
                        .addHeader("Set-Cookie", "p_skey=; Domain=qq.com; Path=/; Max-Age=0")
                    byteArrayOf()
                }
                4 -> {
                    assertEquals(jump, request.url.toString())
                    assertEquals("valid-graph-ticket", parseQQCookies(request.header("Cookie").orEmpty())["p_skey"])
                    response.addHeader("Set-Cookie", "graph_session=activated; Domain=graph.qq.com; Path=/; Secure")
                    byteArrayOf()
                }
                5 -> {
                    val cookies = parseQQCookies(request.header("Cookie").orEmpty())
                    assertEquals("valid-graph-ticket", cookies["p_skey"])
                    assertEquals("activated", cookies["graph_session"])
                    assertFalse(cookies.containsKey("qrsig"))
                    val form = request.body as FormBody
                    val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals(qqHash("valid-graph-ticket", 5381).toString(), values["g_tk"])
                    response.code(302).addHeader("Location",
                        "https://y.qq.com/portal/wx_redirect.html?code=music-code&state=${values["state"]}")
                    byteArrayOf()
                }
                6 -> {
                    assertNull(request.header("Cookie"))
                    val buffer = Buffer()
                    requireNotNull(request.body).writeTo(buffer)
                    val envelope = JSONObject(buffer.readUtf8())
                    assertEquals("music-code", envelope.getJSONObject("qrLogin").getJSONObject("param").getString("code"))
                    """{"code":0,"qrLogin":{"code":0,"data":{"musicid":456,"musickey":"music-key"}}}""".toByteArray()
                }
                else -> error("Unexpected QR request")
            }
            response.body(body.toResponseBody()).build()
        }.build()
        val login = QQMusicQrLogin(http)
        val result = login.poll(login.create())
        assertEquals(QQMusicQrStatus.SUCCESS, result.status)
        val cookie = parseQQCookies(requireNotNull(result.cookie))
        assertEquals("456", cookie["musicid"])
        assertEquals("music-key", cookie["musickey"])
        assertFalse(cookie.keys.any { it in setOf("qrsig", "p_skey", "graph_session") })
        assertEquals(6, calls.size)
    }

    @Test fun `cookie deletion only removes the matching domain and path`() {
        val jar = QQMusicLoginCookies()
        val origin = "https://ssl.ptlogin2.graph.qq.com/check_sig".toHttpUrl()
        fun save(vararg headers: String) = jar.saveFromResponse(origin, headers.map { requireNotNull(Cookie.parse(origin, it)) })
        val graph = "https://graph.qq.com/oauth2.0/authorize".toHttpUrl()
        save("p_skey=graph; Domain=graph.qq.com; Path=/; Secure",
            "p_skey=parent; Domain=qq.com; Path=/; Secure")
        save("p_skey=; Domain=qq.com; Path=/; Max-Age=0")
        assertEquals(listOf("graph"), jar.loadForRequest(graph).map { it.value })
        assertTrue(jar.loadForRequest("https://y.qq.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://graph.qq.com/".toHttpUrl()).isEmpty())
        save("p_skey=; Domain=graph.qq.com; Path=/other; Max-Age=0")
        assertEquals("graph", jar.loadForRequest(graph).single().value)
        save("p_skey=; Domain=graph.qq.com; Path=/; Max-Age=0")
        assertTrue(jar.loadForRequest(graph).isEmpty())
    }

    @Test fun `refreshing QR creates independent cookie sessions`() = runBlocking {
        var created = 0
        val polled = mutableListOf<String?>()
        val login = QQMusicQrLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            val body = if (request.url.encodedPath == "/ptqrshow") {
                response.addHeader("Set-Cookie", "qrsig=session-${++created}; Domain=ptlogin2.qq.com; Path=/")
                png
            } else {
                polled += request.header("Cookie")
                "ptuiCB('66','0','','0','waiting','');".toByteArray()
            }
            response.body(body.toResponseBody()).build()
        }.build())
        val first = login.create()
        val second = login.create()
        assertEquals(QQMusicQrStatus.WAITING_SCAN, login.poll(first).status)
        assertEquals(QQMusicQrStatus.WAITING_SCAN, login.poll(second).status)
        assertEquals(listOf("qrsig=session-1", "qrsig=session-2"), polled)
    }

    @Test fun `OAuth activation preserves signed parameters only for the expected QQ endpoint`() {
        val expected = "https://graph.qq.com/oauth2.0/login_jump?auth=fixture"
        assertEquals(expected, qqLoginJumpUrl(expected))
        assertEquals("https://graph.qq.com/oauth2.0/login_jump", qqLoginJumpUrl(""))
        listOf("https://attacker.example/oauth2.0/login_jump", "http://graph.qq.com/oauth2.0/login_jump",
            "https://graph.qq.com/other", "https://graph.qq.com:8443/oauth2.0/login_jump").forEach {
            assertThrows(QQMusicException::class.java) { qqLoginJumpUrl(it) }
        }
    }

    @Test fun `missing login ticket reports a safe diagnostic code`() = runBlocking {
        val login = QQMusicQrLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            val body = when (request.url.encodedPath) {
                "/ptqrshow" -> {
                    response.addHeader("Set-Cookie", "qrsig=fixture; Domain=ptlogin2.qq.com; Path=/")
                    png
                }
                "/ptqrlogin" -> "ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?ptsigx=fixture','0','ok','');".toByteArray()
                "/check_sig" -> {
                    response.code(302).addHeader("Location", "https://graph.qq.com/oauth2.0/login_jump")
                        .addHeader("Set-Cookie", "p_skey=; Domain=graph.qq.com; Path=/; Max-Age=0")
                    byteArrayOf()
                }
                "/oauth2.0/login_jump" -> byteArrayOf()
                else -> error("Missing ticket must stop authorization")
            }
            response.body(body.toResponseBody()).build()
        }.build())
        try {
            login.poll(login.create())
            fail("Missing ticket must not log in")
        } catch (error: QQMusicException) {
            assertEquals(QQMusicException.Kind.REQUEST_FAILED, error.kind)
            assertEquals("QQ-02", error.diagnosticCode)
            assertFalse(error.message.orEmpty().contains("fixture"))
        }
    }
}
