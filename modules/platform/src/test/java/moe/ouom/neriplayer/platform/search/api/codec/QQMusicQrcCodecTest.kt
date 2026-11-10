package moe.ouom.neriplayer.platform.search.api.codec

import java.util.Base64
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import org.junit.Assert.*
import org.junit.Test

class QQMusicQrcCodecTest {
    @Test fun `QRC uses the existing word lyric renderer and preserves punctuation`() {
        val raw="""<Lyric_1 LyricContent="[1000,1000](A)(1000,400)\"B\"(1400,600)&#10;[2200,500]尾(2200,500)"/>""".replace("\\\"", "\"")
        val encoded=Base64.getEncoder().encodeToString(raw.toByteArray())
        val decoded=requireNotNull(decodeQQMusicLyricPayload(encoded))
        val lines=parseNeteaseLyricsAuto(decoded)
        assertEquals(2,lines.size)
        assertEquals("(A)\"B\"",lines[0].text)
        assertEquals(1000L,lines[0].startTimeMs)
        assertEquals(2000L,lines[0].endTimeMs)
        assertEquals(2,lines[0].words!!.size)
        assertEquals(3,lines[0].words!![0].charCount)
        assertEquals(1400L,lines[0].words!![1].startTimeMs)
        assertEquals("尾",lines[1].text)
    }

    @Test fun `LRC compatibility is preserved and malformed QRC does not become lyrics`() {
        assertEquals("[00:01.00]line",decodeQQMusicLyricPayload("[00:01.00]line"))
        assertNull(decodeQQMusicLyricPayload("<Lyric_1 LyricContent=\"bad\"/>"))
        assertNull(decodeQQMusicLyricPayload("0011223344556677"))
        assertEquals("",decryptQqQrcHex("invalid"))
    }
}
