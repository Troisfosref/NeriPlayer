package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.*
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.cache.*

typealias QQLoad<T> = QQMusicLoadState<T>

class QQMusicBrowseViewModel : ViewModel() {
    private val accounts = AppContainer.qqMusicAccounts
    private val cache = AppContainer.qqMusicBrowseCache
    val account = accounts.state
    val catalog = QQMusicCatalogRepository(accounts, QQMusicClient(AppContainer.sharedOkHttpClient))
    val repository = QQMusicBrowseRepository(accounts, QQMusicClient(AppContainer.sharedOkHttpClient))
    val liked = MutableStateFlow(QQLoad<QQMusicPlaylist>())
    val created = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    val collected = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    val daily = MutableStateFlow(QQLoad<List<SongItem>>())
    val radio = MutableStateFlow(QQLoad<List<SongItem>>())
    val recommended = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    private val revision = MutableStateFlow(0)
    private val libraryRequested = MutableStateFlow(0)
    private val homeRequested = MutableStateFlow(false)
    private var librarySession: Long? = null
    private data class LibraryRequest(val session: Long, val signedIn: Boolean, val requested: Boolean,
                                      val refresh: Int, val libraryRevision: Int)
    fun requestLibrary() { libraryRequested.update { it + 1 } }
    fun requestHome() { homeRequested.value = true }
    init {
        viewModelScope.launch {
            try { accounts.load() } catch (c: CancellationException) { throw c } catch (_: Exception) { return@launch }
            launch {
                combine(account, revision, libraryRequested, accounts.libraryRevision) { a, refresh, requested, change ->
                    LibraryRequest(a.savedAt, a.signedIn, requested > 0, refresh, change)
                }.collectLatest { request ->
                    if (!request.requested || !request.signedIn) {
                        librarySession=null
                        liked.value=QQLoad(loading=false);created.value=QQLoad(loading=false);collected.value=QQLoad(loading=false)
                        return@collectLatest
                    }
                    if (librarySession != request.session) {
                        librarySession=request.session
                        liked.value=QQLoad();created.value=QQLoad();collected.value=QQLoad()
                    }
                    coroutineScope {
                        val session=request.session; val change=request.libraryRevision
                        launch { loadQQMusicCachedData(true, change,
                            read={cache.readLibrary(session,"liked")?.map { it.single() }},
                            fetch={repository.liked()}, save={cache.saveLibrary(session,"liked",listOf(it),change)},
                            publish={liked.value=it}) }
                        launch { loadQQMusicCachedData(true, change,
                            read={cache.readLibrary(session,"created")}, fetch={repository.created()},
                            save={cache.saveLibrary(session,"created",it,change)}, publish={created.value=it}) }
                        launch { loadQQMusicCachedData(true, change,
                            read={cache.readLibrary(session,"collected")}, fetch={repository.collected()},
                            save={cache.saveLibrary(session,"collected",it,change)}, publish={collected.value=it}) }
                    }
                }
            }
            launch {
                combine(account, revision, homeRequested, AppContainer.settingsRepo.homeCardQqMusicFlow) { a, _, requested, enabled ->
                    a.takeIf { requested && enabled }
                }.collectLatest { a ->
                    daily.value=QQLoad();radio.value=QQLoad();recommended.value=QQLoad()
                    if(a?.signedIn == true) coroutineScope {
                        launch { load(daily) { repository.daily() } }
                        launch { load(radio) { repository.radio() } }
                        launch { load(recommended) { repository.recommendations() } }
                    }
                }
            }
        }
    }
    fun refresh() { revision.value++ }
    suspend fun loadPlaylist(playlist: QQMusicPlaylist, force: Boolean, change: Int,
                             publish: (QQLoad<List<SongItem>>) -> Unit) {
        val isPublic = playlist.id.startsWith("album:") || playlist.id.startsWith("artist:")
        val session = if (isPublic) 0L else account.value.savedAt
        loadQQMusicCachedData(force || !isPublic, change,
            read={cache.readTracks(session,playlist.id)},
            fetch={
                when(playlist.id) {
                    "daily" -> repository.daily()
                    "radio" -> repository.radio()
                    else -> loadQQMusicTrackPages { offset ->
                        if(isPublic) catalog.collectionTracks(playlist.id,offset) else repository.tracks(playlist,offset)
                    }
                }
            },
            save={cache.saveTracks(session,playlist.id,it,change)}, publish=publish)
    }
    private suspend fun <T> load(target: MutableStateFlow<QQLoad<T>>, block: suspend () -> T) {
        try { target.value=QQLoad(data=block(),loading=false) }
        catch(c: CancellationException) { throw c }
        catch(_: Exception) { target.value=QQLoad(loading=false,failed=true) }
    }
}
