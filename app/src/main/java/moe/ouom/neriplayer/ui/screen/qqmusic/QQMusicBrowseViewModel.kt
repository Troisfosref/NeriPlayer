package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.*
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicClient

data class QQLoad<T>(val data: T? = null, val loading: Boolean = true, val failed: Boolean = false)

class QQMusicBrowseViewModel : ViewModel() {
    private val accounts = AppContainer.qqMusicAccounts
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
    private val libraryRequested = MutableStateFlow(false)
    private val homeRequested = MutableStateFlow(false)
    fun requestLibrary() { libraryRequested.value = true }
    fun requestHome() { homeRequested.value = true }
    init {
        viewModelScope.launch {
            try { accounts.load() } catch (c: CancellationException) { throw c } catch (_: Exception) { return@launch }
            launch {
                combine(account, revision, libraryRequested, accounts.libraryRevision) { a, _, requested, _ ->
                    a.takeIf { requested }
                }.collectLatest { a ->
                    liked.value=QQLoad();created.value=QQLoad();collected.value=QQLoad()
                    if(a?.signedIn == true) coroutineScope {
                        launch { load(liked) { repository.liked() } }
                        launch { load(created) { repository.created() } }
                        launch { load(collected) { repository.collected() } }
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
    private suspend fun <T> load(target: MutableStateFlow<QQLoad<T>>, block: suspend () -> T) {
        try { target.value=QQLoad(data=block(),loading=false) }
        catch(c: CancellationException) { throw c }
        catch(_: Exception) { target.value=QQLoad(loading=false,failed=true) }
    }
}
