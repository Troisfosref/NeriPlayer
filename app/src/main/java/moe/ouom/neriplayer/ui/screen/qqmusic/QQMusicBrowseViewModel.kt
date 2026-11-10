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
    val repository = QQMusicBrowseRepository(accounts, QQMusicClient(AppContainer.sharedOkHttpClient))
    val profile = MutableStateFlow(QQLoad<QQMusicProfile>())
    val created = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    val collected = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    val daily = MutableStateFlow(QQLoad<List<SongItem>>())
    val radio = MutableStateFlow(QQLoad<List<SongItem>>())
    val recommended = MutableStateFlow(QQLoad<List<QQMusicPlaylist>>())
    private val revision = MutableStateFlow(0)
    init {
        viewModelScope.launch {
            try { accounts.load() } catch (c: CancellationException) { throw c } catch (_: Exception) { return@launch }
            combine(account, revision, AppContainer.settingsRepo.homeCardQqMusicFlow) { a, _, enabled -> a to enabled }.collectLatest { (a, enabled) ->
                profile.value=QQLoad(); created.value=QQLoad();collected.value=QQLoad()
                daily.value=QQLoad();radio.value=QQLoad();recommended.value=QQLoad()
                if(a.signedIn) coroutineScope {
                    launch { load(profile) { repository.profile() } }
                    launch { load(created) { repository.created() } }
                    launch { load(collected) { repository.collected() } }
                    if(enabled) launch { load(daily) { repository.daily() } }
                    if(enabled) launch { load(radio) { repository.radio() } }
                    if(enabled) launch { load(recommended) { repository.recommendations() } }
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
