package moe.ouom.neriplayer.platform.qqmusic.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.QQMusicTrackPage
import org.junit.Assert.*
import org.junit.Test

class QQMusicBrowseCacheTest {
    @Test fun `fresh persistent snapshot opens without a network request`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<List<String>>>()
        loadQQMusicCachedData(false, 0, { QQMusicCacheSnapshot(listOf("cached"), 1_000L) },
            { error("Fresh cache must skip network") }, { error("Nothing changed") }, states::add, { 2_000L })
        assertEquals(listOf(QQMusicLoadState(listOf("cached"), loading=false)), states)
    }

    @Test fun `stale snapshot is visible before network and survives failed refresh`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<List<String>>>()
        loadQQMusicCachedData(false, 0, { QQMusicCacheSnapshot(listOf("old"), 1L) }, {
            assertEquals(listOf("old"), states.single().data)
            assertFalse(states.single().loading)
            throw IllegalStateException("offline")
        }, { error("Failed refresh must not overwrite storage") }, states::add, { 400_000L })
        assertEquals(QQMusicLoadState(listOf("old"), loading=false, failed=true), states.last())
    }

    @Test fun `forced refresh replaces even a fresh snapshot with a successful empty list`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<List<String>>>()
        var saved: List<String>? = null
        loadQQMusicCachedData(true, 0, { QQMusicCacheSnapshot(listOf("removed"), 1_000L) },
            { emptyList() }, { saved=it }, states::add, { 2_000L })
        assertEquals(emptyList<String>(), saved)
        assertEquals(QQMusicLoadState(emptyList<String>(), loading=false), states.last())
    }

    @Test fun `a cloud library change invalidates a recent snapshot`() = runBlocking {
        var fetched = false
        loadQQMusicCachedData(false, 1, { QQMusicCacheSnapshot("old", 1_000L, revision=0) },
            { fetched=true; "updated" }, {}, {}, { 2_000L })
        assertTrue(fetched)
    }

    @Test fun `cancellation neither overwrites complete cache nor becomes a refresh error`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<String>>()
        val cancelled = CancellationException("navigate away")
        try {
            loadQQMusicCachedData(true, 0, { QQMusicCacheSnapshot("complete", 1L) },
                { throw cancelled }, { error("Cancelled request must not be saved") }, states::add)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertEquals(listOf(QQMusicLoadState("complete", loading=false)), states)
    }

    @Test fun `cache read or write failure does not hide a successful fetch`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<String>>()
        loadQQMusicCachedData(false, 0, { throw IllegalStateException("corrupt") },
            { "network" }, { throw IllegalStateException("storage full") }, states::add)
        assertEquals(QQMusicLoadState("network", loading=false), states.last())
    }

    @Test fun `song identity and media MID survive the shared Room record without a stream URL`() {
        val original = song("catalog", "differentFile", 2)
        val record = qqMusicTrackCacheRecord(123L,"album:abc",listOf(original.copy(streamUrl="https://temporary.invalid/ticket")),0,5L)
        val restored = record.qqSongs().single()
        assertEquals(original, restored)
        assertEquals(QQMusicSongRef("catalog", "differentFile", 2), QQMusicSongRef.from(restored))
        assertFalse(record.toString().contains("temporary.invalid"))
    }

    @Test fun `library summary round trip retains counts covers and liked directory`() {
        val lists = listOf(QQMusicPlaylist("favorites","我喜欢的音乐",count=161,directory=201),
            QQMusicPlaylist("123","收藏", "https://cover.invalid/a.jpg", 9))
        assertEquals(lists, qqMusicLibraryCacheRecord(11L,"created",lists,0,5L).libraryPlaylists())
        assertNotEquals(qqMusicCacheKey(11L,"tracks","favorites"), qqMusicCacheKey(12L,"tracks","favorites"))
    }

    @Test fun `freshness rejects clock rollback expiry and changed library revision`() {
        val cached = QQMusicCacheSnapshot("data", 1_000L, 3)
        assertFalse(cached.isFresh(999L,3))
        assertFalse(cached.isFresh(301_000L,3))
        assertFalse(cached.isFresh(2_000L,4))
        assertTrue(cached.isFresh(2_000L,3))
    }

    @Test fun `repeated or failed later page cannot replace an older complete collection`() = runBlocking {
        val states = mutableListOf<QQMusicLoadState<List<SongItem>>>()
        var calls = 0
        val complete = listOf(song("old"))
        loadQQMusicCachedData(true,0,{QQMusicCacheSnapshot(complete,1L)}, {
            loadQQMusicTrackPages { offset ->
                calls++
                check(calls<=2)
                QQMusicTrackPage(listOf(song("new")),offset+1)
            }
        }, { error("Incomplete pages must not replace the cache") }, states::add)
        assertEquals(2,calls)
        assertEquals(QQMusicLoadState(complete,loading=false,failed=true),states.last())
    }

    @Test fun `all pages complete before publish while duplicate songs are collapsed`() = runBlocking {
        val first=song("first");val second=song("second")
        val result=loadQQMusicTrackPages { offset ->
            if(offset==0) QQMusicTrackPage(listOf(first),1) else QQMusicTrackPage(listOf(first,second),null)
        }
        assertEquals(listOf(first,second),result)
    }

    private fun song(mid: String, mediaMid: String = "", type: Int = 0): SongItem {
        val ref = QQMusicSongRef(mid,mediaMid,type)
        return SongItem(ref.numericId,"Fixture","Artist","Album",42L,123_000L,"https://cover.invalid/cover",
            mediaUri=ref.mediaUri,channelId=QQMusicSongRef.CHANNEL,audioId=mid,subAudioId=mediaMid)
    }
}
