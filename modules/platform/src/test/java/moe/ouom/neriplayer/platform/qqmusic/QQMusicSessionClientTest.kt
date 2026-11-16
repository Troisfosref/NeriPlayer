package moe.ouom.neriplayer.platform.qqmusic

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.*
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSessionClient
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QQMusicSessionClientTest {
    @Before fun initializeAndroidHttp() { OkHttp.initialize(ApplicationProvider.getApplicationContext()) }

    @Test fun `renewal sends saved refresh fields with the matching identity and updates all key aliases`() = runBlocking {
        val old = "musicid=123; musickey=old; refresh_key=renew; openid=open; loginType=2"
        var calls = 0
        val client = QQMusicSessionClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("u.y.qq.com",chain.request().url.host)
            assertEquals(old,chain.request().header("Cookie"))
            val buffer = Buffer(); requireNotNull(chain.request().body).writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("stable-device",body.getJSONObject("comm").getString("OpenUDID"))
            assertEquals(2,body.getJSONObject("comm").getInt("tmeLoginType"))
            val request = body.getJSONObject("session")
            assertEquals("music.login.LoginServer",request.getString("module"))
            assertEquals("Login",request.getString("method"))
            val param = request.getJSONObject("param")
            assertEquals(2,param.getInt("loginMode"))
            assertEquals("renew",param.getString("refresh_key"))
            assertEquals("123",param.getString("str_musicid"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"session":{"code":0,"data":{"musicid":123,"musickey":"fresh",
                    "refresh_key":"renew-next","musickeyCreateTime":1000,"keyExpiresIn":7200}}}""".toResponseBody()).build()
        }.build())
        val result = client.renew(old,"stable-device")
        assertEquals("fresh",qqMusicKey(result))
        assertEquals("renew-next",parseQQCookies(result)["refresh_key"])
        assertEquals("7200",parseQQCookies(result)["keyExpiresIn"])
        assertEquals(1,calls)
    }
}
