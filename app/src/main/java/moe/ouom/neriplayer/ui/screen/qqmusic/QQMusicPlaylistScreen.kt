package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.data.identity.stableKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.playlist.*
import moe.ouom.neriplayer.ui.screen.playlist.detail.*
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground

@Composable
internal fun QQMusicPlaylistScreen(playlist:QQMusicPlaylist,onBack:()->Unit,onPlay:(List<SongItem>,Int)->Unit,vm:QQMusicBrowseViewModel=viewModel()) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val account by vm.account.collectAsStateWithLifecycle()
    val libraryRevision by moe.ouom.neriplayer.core.di.AppContainer.qqMusicAccounts.libraryRevision.collectAsStateWithLifecycle()
    val local=remember(context){LocalPlaylistRepository.getInstance(context)}
    val allPlaylists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val lookup=remember(allPlaylists){FavoriteSongLookup(FavoritesPlaylist.firstOrNull(allPlaylists,context)?.songs.orEmpty())}
    val favoriteRepo=remember(context){FavoritePlaylistRepository.getInstance(context)}
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val publicCollection = playlist.id.startsWith("album:") || playlist.id.startsWith("artist:")
    val favoriteTarget = remember(playlist) { playlist.favoriteTarget() }
    val isFavorite = favoriteTarget?.let { target ->
        favorites.any { it.source == target.source && it.id == target.id }
    } == true
    var tracks by remember(playlist.id,account.savedAt){mutableStateOf<List<SongItem>>(emptyList())}
    var loading by remember(playlist.id,account.savedAt){mutableStateOf(false)}
    var failed by remember(playlist.id,account.savedAt){mutableStateOf(false)}
    var showExport by remember{mutableStateOf(false)}
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    fun exitSelection() { selectionMode = false; selectedKeys = emptySet() }
    fun toggleSelection(song: SongItem) {
        val key = song.stableKey()
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }
    BackHandler(selectionMode) { exitSelection() }
    val autoShowKeyboard by moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
        .autoShowKeyboardFlow.collectAsStateWithLifecycle(initialValue = false)
    var refresh by remember{mutableIntStateOf(0)}
    val current by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val shuffle by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeat by PlayerManager.repeatModeFlow.collectAsStateWithLifecycle()
    val snackbar=remember{SnackbarHostState()}
    val listState=rememberLazyListState()
    val searchUi=rememberPlaylistSearchUiState()
    var query by searchUi.query
    var showSearch by searchUi.visible
    var headerFocused by remember{mutableStateOf(false)}
    var dockedFocused by remember{mutableStateOf(false)}
    val input=rememberPlaylistSearchInputState(query,{query=it})
    val focus=remember{FocusRequester()}
    val shown=remember(query,tracks){tracks.filter{query.isBlank() || it.name.contains(query,true)||it.artist.contains(query,true)}}
    val selectedSongs = remember(tracks, selectedKeys) { tracks.filter { it.stableKey() in selectedKeys } }
    val actionSongs = if (selectionMode) selectedSongs else shown
    val currentIndex = shown.indexOfFirst { it.stableKey() == current?.stableKey() }
    LaunchedEffect(playlist.id,refresh,account.savedAt,account.signedIn,libraryRevision) {
        exitSelection()
        if(!account.signedIn && !publicCollection) {
            tracks=emptyList();loading=false;failed=false
            return@LaunchedEffect
        }
        vm.loadPlaylist(playlist,force=refresh>0,change=libraryRevision) { state ->
            tracks=state.data.orEmpty();loading=state.loading;failed=state.failed
        }
    }
    fun toggleSong(song:SongItem,exists:Boolean){scope.launch{
        try { if(exists)local.removeFromFavorites(song) else local.addToFavorites(song) }
        catch(c:CancellationException){throw c}
        catch(_:Exception){snackbar.showSnackbar(context.getString(CoreR.string.qq_action_failed))}
    }}
    val cover=playlist.cover ?: tracks.firstOrNull()?.coverUrl
    PlaylistModernDetailFrame(
        header=PlaylistModernDetailHeader(playlist.id,"qq:${playlist.id}",playlist.title,cover,
            stringResource(CoreR.string.qq_browse_count,tracks.size),false,LocalHasCustomBackground.current),
        search=PlaylistModernDetailSearch(showSearch,{showSearch=it},query,{query=it},input,focus,autoShowKeyboard,
            headerFocused,{headerFocused=it},dockedFocused,{dockedFocused=it}),
        listState=listState,snackbarHostState=snackbar,
        onBack={ if(selectionMode) exitSelection() else onBack() },
        selectionCount=if(selectionMode) selectedSongs.size else null,
        onExitSelection=::exitSelection,
        onLocatePlaying=if(currentIndex >= 0) { { scope.launch { listState.animateScrollToItem(currentIndex + 2) } } } else null,
        normalActions={
            HapticIconButton(onClick={refresh++}){Icon(Icons.Filled.Refresh,stringResource(CoreR.string.qq_browse_refresh))}
            favoriteTarget?.let { target ->
                HapticIconButton(onClick = { scope.launch {
                    try {
                        if (isFavorite) favoriteRepo.removeFavorite(target.id, target.source)
                        else favoriteRepo.addFavorite(
                            id = target.id, name = playlist.title, coverUrl = cover,
                            trackCount = maxOf(tracks.size, playlist.count), source = target.source,
                            browseId = target.browseId, songs = emptyList()
                        )
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { snackbar.showSnackbar(context.getString(CoreR.string.qq_action_failed)) }
                } }) {
                    Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        stringResource(if (isFavorite) CoreR.string.favorite_remove else CoreR.string.favorite_add))
                }
            }
        },
        selectionActions={
            HapticIconButton(onClick={
                GlobalDownloadManager.startBatchDownload(context, selectedSongs)
                exitSelection()
            },enabled=selectedSongs.isNotEmpty()) {
                Icon(Icons.Outlined.Download,stringResource(CoreR.string.cd_download_selected))
            }
            PlaylistSelectionActions(
                allSelected=areDisplayedSongKeysSelected(selectedKeys,shown.map { it.stableKey() }.toSet()),
                onToggleSelectAll={selectedKeys=toggleDisplayedSongSelection(selectedKeys,shown.map { it.stableKey() }.toSet())},
                canExport=selectedSongs.isNotEmpty(),onExport={showExport=true})
        },
        beforeList={if(!account.signedIn && !publicCollection) Text(stringResource(CoreR.string.qq_browse_login)) else QQLoadNotice(loading,failed,false,{refresh++})},
        playbackActions={PlaylistModernPlaybackActions(
            songCount=if(loading)0 else actionSongs.size,shuffleEnabled=shuffle,repeatMode=repeat,
            onPlayInOrder={if(actionSongs.isNotEmpty())onPlay(actionSongs,0)},
            onShufflePlay={if(actionSongs.isNotEmpty())onPlay(actionSongs.shuffled(),0)},
            onToggleShuffle={PlayerManager.setShuffle(!shuffle)},onCycleRepeatMode={PlayerManager.cycleRepeatMode()},
            onExportToLocalPlaylist={showExport=true})},
        content={
            itemsIndexed(shown,key={_,s->s.stableKey()}){index,song->
                PlaylistModernListItemSurface(cover,false){SongRow(index+1,song,
                    current?.channelId==song.channelId&&current?.audioId==song.audioId,playing,lookup.contains(song),::toggleSong,
                    true,selectionMode,song.stableKey() in selectedKeys,{toggleSelection(song)},
                    {selectionMode=true;toggleSelection(song)},{onPlay(shown,index)}, {PlayerManager.addToQueueNext(song)},
                    {PlayerManager.addToQueueEnd(song)},snackbar,false)}
            }
            if(shown.isEmpty()&&!loading&&!failed)item{Text(stringResource(CoreR.string.qq_browse_empty))}
        },
        overlays={if(showExport)PlaylistExportSheet(title=stringResource(CoreR.string.playlist_export_to_local),
            playlists=allPlaylists.filterNot{LocalFilesPlaylist.isSystemPlaylist(it,context)},selectedCount=actionSongs.size,onDismissRequest={showExport=false},
            onCreateAndExport={name->scope.launch{local.createPlaylistWithSongs(name,actionSongs);showExport=false}},
            onExportToPlaylist={p->scope.launch{local.addSongsToPlaylist(p.id,actionSongs);showExport=false}})}
    )
}
