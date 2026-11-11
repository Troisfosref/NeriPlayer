package moe.ouom.neriplayer.ui.screen.qqmusic

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.*
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.ui.screen.tab.home.*
import moe.ouom.neriplayer.ui.screen.tab.library.NeteasePlaylistRow
import moe.ouom.neriplayer.ui.screen.tab.library.LibrarySecondaryTabs
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryPullToRefresh
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import com.google.gson.Gson

internal val LocalQQPlaylistOpen = staticCompositionLocalOf<(QQMusicPlaylist) -> Unit> { {} }
internal fun qqPlaylistRoute(p: QQMusicPlaylist) = "qq_playlist_detail/${Uri.encode(Gson().toJson(p))}"
internal fun QQMusicPlaylist.summary() = PlaylistSummary(id.toLongOrNull() ?: -(directory ?: 201).toLong(),title,cover.orEmpty(),0,count)

@Composable
fun QQMusicLibraryContent(
    listState: LazyListState,
    refreshEnabled: Boolean = true,
    onCategoryChange: () -> Unit = {},
    vm: QQMusicBrowseViewModel = viewModel()
) {
    LaunchedEffect(vm) { vm.requestLibrary() }
    val account by vm.account.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val open = LocalQQPlaylistOpen.current
    val context = LocalContext.current
    val shape = neriRoundedShape(12.dp)
    val refreshing = account.signedIn && (liked.loading || created.loading || collected.loading)

    Column(Modifier.fillMaxSize()) {
        LibrarySecondaryTabs(
            labels = listOf(stringResource(CoreR.string.qq_browse_created), stringResource(CoreR.string.qq_browse_collected)),
            selectedIndex = selectedCategory,
            onSelected = { category ->
                if (category != selectedCategory) {
                    selectedCategory = category
                    scope.launch {
                        listState.scrollToItem(0)
                        onCategoryChange()
                    }
                }
            }
        )
        LibraryPullToRefresh(
            refreshing = refreshing,
            enabled = account.signedIn && refreshEnabled,
            onRefresh = vm::refresh
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp,
                    bottom = LocalMiniPlayerHeight.current + 24.dp)
            ) {
                if (!account.signedIn) {
                    item { Text(stringResource(CoreR.string.qq_browse_login), Modifier.padding(24.dp)) }
                } else if (selectedCategory == 0) {
                    item(key = "liked") {
                        val playlist = (liked.data ?: QQMusicPlaylist("favorites", "", directory = 201))
                            .copy(title = stringResource(CoreR.string.qq_browse_liked))
                        NeteasePlaylistRow(playlist.summary(), shape, context, { open(playlist) }, false,
                            supportingText = if (liked.data != null) stringResource(CoreR.string.qq_browse_count, playlist.count)
                                else stringResource(CoreR.string.settings_qq_music))
                        if (liked.failed) QQLoadNotice(false, true, false, vm::refresh)
                    }
                    item { QQLoadNotice(created.loading, created.failed, false, vm::refresh) }
                    items(created.data.orEmpty(), key = { "created:${it.id}" }) { playlist ->
                        NeteasePlaylistRow(playlist.summary(), shape, context, { open(playlist) }, false,
                            supportingText = stringResource(CoreR.string.qq_browse_count, playlist.count))
                    }
                } else {
                    item { QQLoadNotice(collected.loading, collected.failed, collected.data?.isEmpty() == true, vm::refresh) }
                    items(collected.data.orEmpty(), key = { "collected:${it.id}" }) { playlist ->
                        NeteasePlaylistRow(playlist.summary(), shape, context, { open(playlist) }, false,
                            supportingText = stringResource(CoreR.string.qq_browse_count, playlist.count))
                    }
                }
            }
        }
    }
}

@Composable
internal fun QQMusicHomeContent(refreshKey: Int, favoriteSongLookup: FavoriteSongLookup,
    onFavoriteToggle: (SongItem,Boolean)->Unit,onShowSnackbar:(String)->Unit,favoriteKeys:Set<String> = emptySet(),vm:QQMusicBrowseViewModel=viewModel()) {
    LaunchedEffect(vm) { vm.requestHome() }
    val account by vm.account.collectAsStateWithLifecycle()
    val enabled by AppContainer.settingsRepo.homeCardQqMusicFlow.collectAsState(initial=true)
    val daily by vm.daily.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    val open=LocalQQPlaylistOpen.current
    LaunchedEffect(refreshKey,enabled) { if(enabled && refreshKey>0)vm.refresh() }
    if(!enabled || !account.signedIn)return
    Column(Modifier.fillMaxWidth()) {
        listOf("daily" to (stringResource(CoreR.string.qq_browse_daily) to daily),"radio" to (stringResource(CoreR.string.qq_browse_radio) to radio)).forEach { (id,section) ->
            val (title,state)=section
            val songs = state.data.orEmpty()
            SectionHeader(Icons.Outlined.Radar,title)
            QQLoadNotice(state.loading,state.failed,state.data?.isEmpty()==true,vm::refresh)
            if(songs.isNotEmpty()) {
                ResponsiveSongPagerList(songs,{tracks,index->PlayerManager.playPlaylist(tracks,index)},favoriteSongLookup,onFavoriteToggle,onShowSnackbar,false)
                TextButton(onClick={open(QQMusicPlaylist(id,title,songs.firstOrNull()?.coverUrl,songs.size))}){Text(stringResource(CoreR.string.qq_browse_more))}
            }
        }
        SectionHeader(Icons.Outlined.Star,stringResource(CoreR.string.qq_browse_recommended))
        QQLoadNotice(recommended.loading,recommended.failed,recommended.data?.isEmpty()==true,vm::refresh)
        val recommendedPlaylists = recommended.data.orEmpty()
        if (recommendedPlaylists.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recommendedPlaylists, key={it.id}) { p ->
                    val summary = p.summary()
                    RadarPlaylistCard(
                        playlist=summary,
                        isFavorite=favoriteKeys.contains("qqmusic:${summary.id}"),
                        onClick={open(p)},
                        onShowSnackbar=onShowSnackbar,
                        offlineMode=false,
                        modifier=Modifier.width(148.dp),
                        source="qqmusic",
                        badgeText=stringResource(CoreR.string.qq_browse_recommended),
                        countText=stringResource(CoreR.string.qq_browse_count,p.count)
                    )
                }
            }
        }
    }
}

@Composable
internal fun QQLoadNotice(loading:Boolean,failed:Boolean,empty:Boolean,retry:()->Unit) {
    when {
        loading->CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        failed->TextButton(onClick=retry){Text(stringResource(CoreR.string.qq_browse_retry))}
        empty->Text(stringResource(CoreR.string.qq_browse_empty),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
