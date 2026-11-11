package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistPage
import moe.ouom.neriplayer.platform.qqmusic.artist.QQMusicArtistProfile
import moe.ouom.neriplayer.platform.qqmusic.cache.QQMusicArtistSnapshot
import moe.ouom.neriplayer.platform.qqmusic.cache.QQMusicCacheSnapshot
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.viewmodel.artist.ArtistDetailHeader
import moe.ouom.neriplayer.ui.viewmodel.artist.ArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import java.nio.ByteBuffer
import java.security.MessageDigest

class QQMusicArtistDetailViewModel internal constructor(
    private val profile: suspend (QQMusicPlaylist) -> QQMusicArtistProfile,
    private val songs: suspend (String,Int) -> QQMusicArtistPage<SongItem>,
    private val albums: suspend (String,Int) -> QQMusicArtistPage<QQMusicPlaylist>,
    private val read: suspend (String) -> QQMusicCacheSnapshot<QQMusicArtistSnapshot>?,
    private val save: suspend (QQMusicArtistSnapshot) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val albumCount: suspend (String) -> Int = { error("Album count lookup is not configured") }
) : ViewModel() {
    constructor() : this(AppContainer.qqMusicArtistRepository::profile,AppContainer.qqMusicArtistRepository::songs,
        AppContainer.qqMusicArtistRepository::albums,AppContainer.qqMusicArtistCache::read,AppContainer.qqMusicArtistCache::save,
        albumCount=AppContainer.qqMusicAlbumCounts::count)
    private val state=MutableStateFlow(ArtistDetailUiState())
    val uiState=state.asStateFlow()
    private var artist: QQMusicPlaylist?=null
    private var offline=false
    private var snapshot: QQMusicArtistSnapshot?=null
    private var complete=false
    private var generation=0
    private var initialJob: Job?=null
    private var songsJob: Job?=null
    private var albumsJob: Job?=null
    private val countJobs=mutableMapOf<String,Job>()
    private val requestedCounts=mutableSetOf<String>()
    private val failedCounts=mutableSetOf<String>()
    private val cacheWrites=Mutex()

    fun start(summary: QQMusicPlaylist, offlineMode: Boolean = false, force: Boolean = false) {
        if(!force && artist?.id==summary.id && offline==offlineMode) return
        val same=artist?.id==summary.id
        artist=summary;offline=offlineMode;generation++
        val owner=generation
        initialJob?.cancel();songsJob?.cancel();albumsJob?.cancel()
        countJobs.values.forEach { it.cancel() };countJobs.clear();failedCounts.clear()
        if(!same) { snapshot=null;complete=false;requestedCounts.clear() }
        val mid=summary.id.removePrefix("artist:")
        val placeholder=QQMusicArtistProfile(mid,summary.title,summary.cover,summary.cover)
        state.value=snapshot?.presentation()?.copy(loading=false) ?: ArtistDetailUiState(header=placeholder.header(0,0))
        initialJob=viewModelScope.launch {
            val cached=attempt { read(mid) }.getOrNull()
            if(owner!=generation) return@launch
            if(cached!=null) {
                val restored=cached.data.withKnownAlbumCounts(snapshot)
                snapshot=restored;complete=true;state.value=restored.presentation()
                retryRequestedCounts()
            }
            if(offlineMode) {
                if(snapshot==null) state.value=state.value.copy(loading=false,error=loadError())
                return@launch
            }
            if(!force && cached?.isFresh(now(),0)==true) return@launch
            val results=coroutineScope {
                val p=async { attempt { profile(summary) } }
                val s=async { attempt { songs(mid,0) } }
                val a=async { attempt { albums(mid,0) } }
                Triple(p.await(),s.await(),a.await())
            }
            if(owner!=generation) return@launch
            val old=snapshot
            val resolvedProfile=results.first.getOrNull()?.let { fresh ->
                if(fresh.introduction.isBlank() && old!=null) fresh.copy(introduction=old.profile.introduction) else fresh
            } ?: old?.profile ?: placeholder
            val loaded=QQMusicArtistSnapshot(resolvedProfile,
                results.second.getOrNull() ?: old?.songs ?: QQMusicArtistPage(emptyList(),null,0),
                results.third.getOrNull() ?: old?.albums ?: QQMusicArtistPage(emptyList(),null,0)).withKnownAlbumCounts(old)
            complete=results.first.isSuccess && results.second.isSuccess && results.third.isSuccess
            snapshot=loaded
            state.value=loaded.presentation().copy(error=if(complete) null else loadError())
            retryRequestedCounts()
            persist(owner)
        }
    }

    fun retry() { artist?.let { start(it,offline,force=true) } }
    fun albumTarget(album: AlbumSummary): QQMusicPlaylist? =
        snapshot?.albums?.items?.firstOrNull { qqArtistPresentationId(it.id)==album.id }

    /** Called by composed album rows, so opening a singer never eagerly queries all album counts. */
    fun requestAlbumCount(album: AlbumSummary) {
        val target=albumTarget(album) ?: return
        requestedCounts+=target.id
        if(offline || target.count>=0 || target.id in countJobs || target.id in failedCounts) return
        val owner=generation
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=attempt { albumCount(target.id.removePrefix("album:")).also { require(it>=0) } }
                if(owner!=generation) return@launch
                val current=snapshot ?: return@launch
                if(result.isSuccess) {
                    val items=current.albums.items.map { if(it.id==target.id) it.copy(count=result.getOrThrow()) else it }
                    val updated=current.copy(albums=current.albums.copy(items=items))
                    snapshot=updated
                    state.value=state.value.copy(albums=updated.presentation().albums)
                    persist(owner)
                } else failedCounts+=target.id
            } finally { if(owner==generation) countJobs.remove(target.id) }
        }
        countJobs[target.id]=job
        job.start()
    }

    private fun retryRequestedCounts() {
        snapshot?.albums?.items?.filter { it.id in requestedCounts && it.count<0 }?.forEach {
            requestAlbumCount(AlbumSummary(qqArtistPresentationId(it.id),it.title,it.cover.orEmpty(),it.count))
        }
    }

    private suspend fun persist(owner: Int) {
        cacheWrites.withLock {
            if(owner==generation && complete) snapshot?.let { current -> attempt { save(current) } }
        }
    }

    private fun QQMusicArtistSnapshot.withKnownAlbumCounts(previous: QQMusicArtistSnapshot?): QQMusicArtistSnapshot {
        val counts=previous?.albums?.items.orEmpty().filter { it.count>0 }.associate { it.id to it.count }
        return copy(albums=albums.copy(items=albums.items.map { item ->
            counts[item.id]?.takeIf { item.count<0 }?.let { item.copy(count=it) } ?: item
        }))
    }

    fun loadMoreSongs() {
        val old=snapshot ?: return
        val offset=old.songs.nextOffset ?: return
        if(offline || state.value.songsLoadingMore || initialJob?.isActive==true) return
        val owner=generation
        state.value=state.value.copy(songsLoadingMore=true,error=null)
        songsJob=viewModelScope.launch {
            val result=attempt {
                val page=songs(old.profile.mid,offset)
                val items=(old.songs.items+page.items).distinctBy { it.audioId }
                val next=page.nextOffset
                check(next==null || (next>offset && items.size>old.songs.items.size))
                page.copy(items=items)
            }
            if(owner!=generation) return@launch
            if(result.isSuccess) {
                val updated=(snapshot ?: return@launch).copy(songs=result.getOrThrow())
                snapshot=updated
                state.value=updated.presentation().copy(albumsLoadingMore=state.value.albumsLoadingMore)
                persist(owner)
            } else state.value=state.value.copy(songsLoadingMore=false,error=loadError())
        }
    }

    fun loadMoreAlbums() {
        val old=snapshot ?: return
        val offset=old.albums.nextOffset ?: return
        if(offline || state.value.albumsLoadingMore || initialJob?.isActive==true) return
        val owner=generation
        state.value=state.value.copy(albumsLoadingMore=true,error=null)
        albumsJob=viewModelScope.launch {
            val result=attempt {
                val page=albums(old.profile.mid,offset)
                val items=(old.albums.items+page.items).distinctBy { it.id }
                val next=page.nextOffset
                check(next==null || (next>offset && items.size>old.albums.items.size))
                page.copy(items=items)
            }
            if(owner!=generation) return@launch
            if(result.isSuccess) {
                val current=snapshot ?: return@launch
                val updated=current.copy(albums=result.getOrThrow()).withKnownAlbumCounts(current)
                snapshot=updated
                state.value=updated.presentation().copy(songsLoadingMore=state.value.songsLoadingMore)
                persist(owner)
            } else state.value=state.value.copy(albumsLoadingMore=false,error=loadError())
        }
    }

    private fun QQMusicArtistSnapshot.presentation() = ArtistDetailUiState(loading=false,
        header=profile.header(songs.total,albums.total),songs=songs.items,
        albums=albums.items.map { AlbumSummary(qqArtistPresentationId(it.id),it.title,it.cover.orEmpty(),it.count) },
        songsHasMore=!offline && songs.nextOffset!=null,albumsHasMore=!offline && albums.nextOffset!=null)

    private fun QQMusicArtistProfile.header(songCount: Int, albumCount: Int) = ArtistDetailHeader(
        qqArtistPresentationId("artist:$mid"),name,background.orEmpty(),avatar.orEmpty(),alias,introduction,
        songCount,albumCount,followed=false,canFollow=false)
    private fun loadError() = UiText.Resource(CoreR.string.qq_browse_retry)
}

internal fun qqArtistPresentationId(id: String): Long = ByteBuffer.wrap(
    MessageDigest.getInstance("SHA-256").digest("qqmusic:$id".toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
catch(cancelled: CancellationException) { throw cancelled }
catch(error: Exception) { Result.failure(error) }
