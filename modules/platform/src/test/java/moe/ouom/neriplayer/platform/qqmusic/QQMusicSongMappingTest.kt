package moe.ouom.neriplayer.platform.qqmusic

import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QQMusicSongMappingTest {
    @Test fun `legacy search and modern detail produce the same track identity`() {
        val legacy = JSONObject("""{"songmid":"catalogMid","songname":"Song","interval":240,
            "albummid":"albumMid","albumname":"Album","singer":[{"name":"Artist"}]}""")
        val detail = JSONObject("""{"mid":"catalogMid","title":"Song","interval":240,
            "file":{"media_mid":"actualFile"},"album":{"mid":"albumMid","name":"Album"},"singer":[{"name":"Artist"}]}""")
        val search = requireNotNull(mapQQMusicSong(legacy))
        val full = requireNotNull(mapQQMusicSong(detail))
        assertEquals(search.id, full.id)
        assertEquals("catalogMid", QQMusicSongRef.from(search)?.mid)
        assertEquals("actualFile", QQMusicSongRef.from(full)?.mediaMid)
        assertEquals(240000L, full.durationMs)
        assertEquals("Artist", full.artist)
    }

    @Test fun `missing catalog MID is rejected instead of masquerading as NetEase`() {
        assertNull(mapQQMusicSong(JSONObject("""{"songid":123,"songname":"Unknown"}""")))
        assertNull(mapQQMusicSong(JSONObject("""{"songmid":"../wrong"}""")))
    }

    @Test fun `synced audio identity can reconstruct a source without a media URI`() {
        val song = requireNotNull(mapQQMusicSong(JSONObject("""{"mid":"catalogMid","file":{"media_mid":"actualFile"}}""")))
        val restored = song.copy(mediaUri = null)
        assertEquals(QQMusicSongRef.from(song), QQMusicSongRef.from(restored))
    }
}
