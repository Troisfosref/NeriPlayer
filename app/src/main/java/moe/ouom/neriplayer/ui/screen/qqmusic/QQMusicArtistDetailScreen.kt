package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.artist.ArtistDetailContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QQMusicArtistDetailScreen(artist: QQMusicPlaylist, onBack: () -> Unit,
    onPlay: (List<SongItem>,Int) -> Unit, offlineMode: Boolean = false,
    vm: QQMusicArtistDetailViewModel = viewModel(key="qq_artist:${artist.id}")) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val open=LocalQQPlaylistOpen.current
    var selected by rememberSaveable(artist.id) { mutableIntStateOf(0) }
    val songsState=rememberSaveable(artist.id,saver=LazyListState.Saver) { LazyListState() }
    val albumsState=rememberSaveable(artist.id,saver=LazyListState.Saver) { LazyListState() }
    LaunchedEffect(artist.id,offlineMode) { vm.start(artist,offlineMode) }
    Surface(Modifier.fillMaxSize(),color=Color.Transparent) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title={ Text(ui.header?.name ?: artist.title,maxLines=1,overflow=TextOverflow.Ellipsis) },
                navigationIcon={ HapticIconButton(onClick=onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(CoreR.string.cd_back))
                } },
                actions={ HapticIconButton(onClick=vm::retry,enabled=!offlineMode && !ui.loading) {
                    Icon(Icons.Filled.Refresh,stringResource(CoreR.string.qq_browse_refresh))
                } },windowInsets=WindowInsets.statusBars,
                colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Transparent))
            ArtistDetailContent(ui=ui,listState=if(selected==0) songsState else albumsState,
                selectedTab=selected,onTabSelected={selected=it},onRetry=vm::retry,onToggleFollow={},
                onLoadMoreSongs=vm::loadMoreSongs,onLoadMoreAlbums=vm::loadMoreAlbums,
                onSongClick=onPlay,onAlbumClick={vm.albumTarget(it)?.let(open)},offlineMode=offlineMode)
        }
    }
}
