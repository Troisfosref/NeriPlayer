package moe.ouom.neriplayer.ui.screen.qqmusic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistPage
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistProfile
import moe.ouom.neriplayer.platform.qqmusic.cache.QQMusicArtistSnapshot
import moe.ouom.neriplayer.platform.qqmusic.cache.QQMusicCacheSnapshot
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QQMusicArtistDetailViewModelTest {
    private val artist=QQMusicPlaylist("artist:mid","Artist")
    private val album=QQMusicPlaylist("album:albumMid","Album",count=9)
    private val cached=QQMusicArtistSnapshot(QQMusicArtistProfile("mid","Artist",null,null,introduction="Full biography"),
        QQMusicArtistPage(listOf(song("first")),1,2),QQMusicArtistPage(listOf(album),1,2))

    @Test fun `only requested unknown albums are resolved and actual counts update both UI and cache`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unknown=cached.copy(albums=cached.albums.copy(items=listOf(album.copy(count=-1),
                QQMusicPlaylist("album:other","Other",count=-1))))
            val calls=mutableListOf<String>()
            val gate=CompletableDeferred<Int>()
            var saved: QQMusicArtistSnapshot?=null
            val vm=QQMusicArtistDetailViewModel({error("Fresh cache")},{_,_->error("Fresh cache")},
                {_,_->error("Fresh cache")},{QQMusicCacheSnapshot(unknown,1_000L)},{saved=it},{2_000L},
                albumCount={mid->calls+=mid;gate.await()})
            vm.start(artist);advanceUntilIdle()
            assertTrue(calls.isEmpty())
            val row=vm.uiState.value.albums.first()
            assertEquals(-1,row.size)
            vm.requestAlbumCount(row);vm.requestAlbumCount(row);runCurrent()
            assertEquals(listOf("albumMid"),calls)
            gate.complete(12);advanceUntilIdle()
            assertEquals(listOf(12,-1),vm.uiState.value.albums.map {it.size})
            assertEquals(listOf(12,-1),saved?.albums?.items?.map {it.count})
            assertEquals(12,vm.albumTarget(vm.uiState.value.albums.first())?.count)
        } finally {Dispatchers.resetMain()}
    }

    @Test fun `count lookup failure leaves unknown metadata and a manual refresh retries it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unknown=cached.copy(albums=cached.albums.copy(items=listOf(album.copy(count=-1))))
            var calls=0;var fail=true
            val vm=QQMusicArtistDetailViewModel({unknown.profile},{_,_->unknown.songs},{_,_->unknown.albums},
                {QQMusicCacheSnapshot(unknown,1_000L)},{},{2_000L},
                albumCount={calls++;if(fail) error("offline") else 15})
            vm.start(artist);advanceUntilIdle()
            vm.requestAlbumCount(vm.uiState.value.albums.single());advanceUntilIdle()
            vm.requestAlbumCount(vm.uiState.value.albums.single());advanceUntilIdle()
            assertEquals(1,calls);assertEquals(-1,vm.uiState.value.albums.single().size)
            assertNull(vm.uiState.value.error)
            fail=false;vm.retry();advanceUntilIdle()
            assertEquals(2,calls);assertEquals(15,vm.uiState.value.albums.single().size)
        } finally {Dispatchers.resetMain()}
    }

    @Test fun `concurrent count completions cannot persist an older incomplete set of counts last`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unknown=cached.copy(albums=cached.albums.copy(items=listOf(album.copy(count=-1),
                QQMusicPlaylist("album:other","Other",count=-1))))
            val writing=CompletableDeferred<Unit>()
            val saved=mutableListOf<QQMusicArtistSnapshot>();var writes=0
            val vm=QQMusicArtistDetailViewModel({error("Fresh cache")},{_,_->error("Fresh cache")},
                {_,_->error("Fresh cache")},{QQMusicCacheSnapshot(unknown,1_000L)},
                {if(++writes==1) writing.await();saved+=it},{2_000L},albumCount={if(it=="albumMid") 9 else 11})
            vm.start(artist);advanceUntilIdle()
            vm.uiState.value.albums.forEach(vm::requestAlbumCount);runCurrent()
            assertEquals(listOf(9,11),vm.uiState.value.albums.map {it.size})
            writing.complete(Unit);advanceUntilIdle()
            assertEquals(listOf(9,11),saved.last().albums.items.map {it.count})
        } finally {Dispatchers.resetMain()}
    }

    @Test fun `switching artists discards a late album count response`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unknown=cached.copy(albums=cached.albums.copy(items=listOf(album.copy(count=-1))))
            val gate=CompletableDeferred<Unit>()
            val vm=QQMusicArtistDetailViewModel({QQMusicArtistProfile("new","New",null,null)},
                {_,_->unknown.songs},{_,_->QQMusicArtistPage(listOf(QQMusicPlaylist("album:newAlbum","New album",count=-1)),null,1)},
                {mid->if(mid=="mid") QQMusicCacheSnapshot(unknown,1_000L) else null},{},{2_000L},
                albumCount={withContext(NonCancellable){gate.await()};12})
            vm.start(artist);advanceUntilIdle()
            vm.requestAlbumCount(vm.uiState.value.albums.single());runCurrent()
            vm.start(QQMusicPlaylist("artist:new","New"));runCurrent()
            gate.complete(Unit);advanceUntilIdle()
            assertEquals("New",vm.uiState.value.header?.name)
            assertEquals("New album",vm.uiState.value.albums.single().name)
            assertEquals(-1,vm.uiState.value.albums.single().size)
        } finally {Dispatchers.resetMain()}
    }

    @Test fun `album pagination does not overwrite a count resolved while the page was loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unknown=cached.copy(albums=cached.albums.copy(items=listOf(album.copy(count=-1))))
            val page=CompletableDeferred<Unit>()
            val vm=QQMusicArtistDetailViewModel({unknown.profile},{_,_->unknown.songs},
                {_,_->page.await();QQMusicArtistPage(listOf(QQMusicPlaylist("album:other","Other",count=4)),null,2)},
                {QQMusicCacheSnapshot(unknown,1_000L)},{},{2_000L},albumCount={12})
            vm.start(artist);advanceUntilIdle()
            vm.loadMoreAlbums();runCurrent()
            vm.requestAlbumCount(vm.uiState.value.albums.single());runCurrent()
            assertEquals(12,vm.uiState.value.albums.single().size)
            page.complete(Unit);advanceUntilIdle()
            assertEquals(listOf(12,4),vm.uiState.value.albums.map {it.size})
        } finally {Dispatchers.resetMain()}
    }

    @Test fun `fresh cached artist opens without network and album clicks retain the original MID`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm=QQMusicArtistDetailViewModel({error("No network")},{_,_->error("No network")},
                {_,_->error("No network")},{QQMusicCacheSnapshot(cached,1_000L)},{error("No change")},{2_000L})
            vm.start(artist);advanceUntilIdle()
            val ui=vm.uiState.value
            assertEquals("Full biography",ui.header?.briefDesc)
            assertEquals(false,ui.header?.canFollow)
            assertEquals("album:albumMid",vm.albumTarget(ui.albums.single())?.id)
            assertFalse(ui.loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed refresh retains cached songs while another section can update`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val second=QQMusicPlaylist("album:second","Second")
            val vm=QQMusicArtistDetailViewModel({cached.profile},{_,_->error("song request failed")},
                {_,_->QQMusicArtistPage(listOf(second),null,1)},
                {QQMusicCacheSnapshot(cached,1L)},{error("Partial refresh must not overwrite a complete cache")},{400_000L})
            vm.start(artist);advanceUntilIdle()
            assertEquals(listOf("first"),vm.uiState.value.songs.map { it.audioId })
            assertEquals(listOf("Second"),vm.uiState.value.albums.map { it.name })
            assertNotNull(vm.uiState.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed extra page preserves the cursor and complete songs for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val offsets=mutableListOf<Int>();var fail=true
            val vm=QQMusicArtistDetailViewModel({cached.profile},{_,offset->
                offsets+=offset
                if(fail) error("offline") else QQMusicArtistPage(listOf(song("first"),song("second")),null,2)
            },{_,_->cached.albums},{QQMusicCacheSnapshot(cached,1_000L)},{},{2_000L})
            vm.start(artist);advanceUntilIdle()
            vm.loadMoreSongs();advanceUntilIdle()
            assertEquals(1,vm.uiState.value.songs.size);assertTrue(vm.uiState.value.songsHasMore)
            fail=false;vm.loadMoreSongs();advanceUntilIdle()
            assertEquals(listOf(1,1),offsets)
            assertEquals(listOf("first","second"),vm.uiState.value.songs.map { it.audioId })
            assertFalse(vm.uiState.value.songsHasMore)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cached artist is readable offline without offering remote pagination`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm=QQMusicArtistDetailViewModel({error("Offline")},{_,_->error("Offline")},
                {_,_->error("Offline")},{QQMusicCacheSnapshot(cached,1L)},{},{400_000L})
            vm.start(artist,offlineMode=true);advanceUntilIdle()
            assertEquals(1,vm.uiState.value.songs.size)
            assertFalse(vm.uiState.value.songsHasMore);assertFalse(vm.uiState.value.albumsHasMore)
            assertNull(vm.uiState.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `switching artist ignores a late response from the previous artist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate=CompletableDeferred<Unit>()
            val vm=QQMusicArtistDetailViewModel({summary->
                val mid=summary.id.removePrefix("artist:")
                if(mid=="old") withContext(NonCancellable) {gate.await()}
                QQMusicArtistProfile(mid,mid,null,null)
            },{mid,_->QQMusicArtistPage(listOf(song(mid)),null,1)},
                {_,_->QQMusicArtistPage(emptyList(),null,0)},{null},{})
            vm.start(QQMusicPlaylist("artist:old","Old"));runCurrent()
            vm.start(QQMusicPlaylist("artist:new","New"));runCurrent()
            gate.complete(Unit);advanceUntilIdle()
            assertEquals("new",vm.uiState.value.header?.name)
            assertEquals(listOf("new"),vm.uiState.value.songs.map { it.audioId })
        } finally { Dispatchers.resetMain() }
    }

    private fun song(mid: String): SongItem {
        val ref=QQMusicSongRef(mid)
        return SongItem(ref.numericId,mid,"Artist","Album",0L,12_000L,null,
            mediaUri=ref.mediaUri,channelId=QQMusicSongRef.CHANNEL,audioId=ref.mid,subAudioId=ref.mediaMid)
    }
}
