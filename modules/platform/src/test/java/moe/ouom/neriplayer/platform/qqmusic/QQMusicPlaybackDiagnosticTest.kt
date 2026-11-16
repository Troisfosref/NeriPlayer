package moe.ouom.neriplayer.platform.qqmusic

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QQMusicPlaybackDiagnosticTest {
    @Test fun `response summary identifies refusal and preview paths without leaking raw data`() {
        val response = JSONObject("""{"sip":["https://isure.stream.qqmusic.qq.com/?secret=hidden"],
            "authst":"credential-secret","midurlinfo":[
            {"result":104003,"purl":""},
            {"result":0,"purl":"M500file.mp3?vkey=secret-ticket&src=preview"},
            {"result":0,"purl":"https://isure.stream.qqmusic.qq.com/C400file.m4a?vkey=secret-ticket"}]}""")
        val line = qqPlaybackResponseDiagnostic(response)
        assertTrue(line.contains("codes=0,104003"))
        assertTrue(line.contains("previewCandidates=2"))
        assertTrue(line.contains("cdnRoots=1"))
        for (secret in listOf("secret", "hidden", "credential", "http", "vkey", "M500", "C400"))
            assertFalse(line.contains(secret))
    }

    @Test fun `error diagnostics report API code without including cookies or response text`() = runBlocking {
        val traces = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":104003,"message":"credential-secret"}""".toResponseBody()).build()
        }.build()
        try {
            QQMusicClient(http, traces::add).playback("catalogMid", "mediaMid", 0, QQMusicQuality.STANDARD,
                "musicid=123; musickey=credential-secret")
            fail("Refusal must propagate")
        } catch (e: QQMusicException) { assertEquals("104003", e.diagnosticCode) }
        val log = traces.joinToString("\n")
        assertTrue(log.contains("phase=failure route=android"))
        assertTrue(log.contains("code=104003"))
        assertTrue(log.contains("playbackKeyPresent=true"))
        assertFalse(log.contains("credential-secret"))
        assertFalse(log.contains("musicid=123"))
    }

    @Test fun `diagnostic failures never change a successful playback API result`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"req_1":{"code":0,"data":{"midurlinfo":[]}}}""".toResponseBody()).build()
        }.build()
        assertEquals(0, QQMusicClient(http) { error("Log storage unavailable") }
            .playback("catalogMid", "fileMid", 0, QQMusicQuality.STANDARD, "").getJSONArray("midurlinfo").length())
        assertEquals("invalid", qqPlaybackDiagnosticMid("catalog\nprivate=value"))
        assertEquals("missing", qqPlaybackDiagnosticMid(""))
    }
}
