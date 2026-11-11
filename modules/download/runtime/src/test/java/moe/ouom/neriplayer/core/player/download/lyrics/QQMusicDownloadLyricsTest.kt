package moe.ouom.neriplayer.core.player.download.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import org.junit.Assert.*
import org.junit.Test

class QQMusicDownloadLyricsTest {
    @Test fun `QQ downloads retain original translation and romanization without requiring metadata`() = runBlocking {
        var nativeCalls = 0
        var romaCalls = 0
        val result = resolveQQMusicDownloadLyrics(song(), {
            assertEquals("catalogMid", it); nativeCalls++
            "[00:01.00]original" to "[00:01.00]translation"
        }, {
            assertEquals("catalogMid", it); romaCalls++
            "[00:01.00]romanized"
        })
        assertEquals(QQMusicDownloadLyrics("[00:01.00]original", "[00:01.00]translation", "[00:01.00]romanized"), result)
        assertEquals(1, nativeCalls)
        assertEquals(1, romaCalls)
    }

    @Test fun `explicitly cleared lyric variants never trigger remote replacement`() = runBlocking {
        val result = resolveQQMusicDownloadLyrics(song().copy(matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = ""),
            { error("Cleared lyrics must not be fetched") }, { error("Cleared roma must not be fetched") })
        assertEquals(QQMusicDownloadLyrics(null, null, null), result)
    }

    @Test fun `custom original and romanization survive while missing translation is fetched`() = runBlocking {
        val result = resolveQQMusicDownloadLyrics(song().copy(matchedLyric = "custom", matchedRomanizedLyric = "custom roma"),
            { "remote" to "translation" }, { error("Custom roma must be retained") })
        assertEquals(QQMusicDownloadLyrics("custom", "translation", "custom roma"), result)
    }

    @Test fun `completed local overrides do not cause an extra remote request just for roma`() = runBlocking {
        val result = resolveQQMusicDownloadLyrics(song().copy(matchedLyric = "custom", matchedTranslatedLyric = "translation"),
            { error("No native fetch needed") }, { error("No additional roma fetch needed") })
        assertEquals(QQMusicDownloadLyrics("custom", "translation", null), result)
    }

    @Test fun `cancelled native request does not start romanization request`() = runBlocking {
        val cancelled = CancellationException("fixture cancelled")
        try {
            resolveQQMusicDownloadLyrics(song(), { throw cancelled }, { error("Must not continue after cancellation") })
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun `missing remote identity keeps user lyrics and never invents a catalog MID`() = runBlocking {
        val result = resolveQQMusicDownloadLyrics(song().copy(mediaUri = "file:///audio.flac", matchedRomanizedLyric = "custom"),
            { error("No valid MID") }, { error("No valid MID") })
        assertEquals(QQMusicDownloadLyrics(null, null, "custom"), result)
    }

    private fun song(): SongItem {
        val ref = QQMusicSongRef("catalogMid", "fileMid")
        return SongItem(ref.numericId, "Fixture", "Artist", "Album", 0L, 120000L, null,
            mediaUri = ref.mediaUri, channelId = QQMusicSongRef.CHANNEL,
            audioId = ref.mid, subAudioId = ref.mediaMid)
    }
}
