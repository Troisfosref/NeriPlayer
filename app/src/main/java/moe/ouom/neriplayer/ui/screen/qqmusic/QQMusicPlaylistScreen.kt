package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
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
    val local=remember(context){LocalPlaylistRepository.getInstance(context)}
    val allPlaylists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val lookup=remember(allPlaylists){FavoriteSongLookup(FavoritesPlaylist.firstOrNull(allPlaylists,context)?.songs.orEmpty())}
    val favoriteRepo=remember(context){FavoritePlaylistRepository.getInstance(context)}
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val summary=playlist.summary()
    val isFavorite=favorites.any {it.source=="qqmusic" && it.id==summary.id}
    var tracks by remember(playlist.id){mutableStateOf<List<SongItem>>(emptyList())}
    var loading by remember{mutableStateOf(false)}
    var failed by remember{mutableStateOf(false)}
    var showExport by remember{mutableStateOf(false)}
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
    LaunchedEffect(playlist,refresh,account.savedAt) {
        tracks=emptyList();loading=true;failed=false
        try {
            when(playlist.id) {
                "daily"->tracks=vm.repository.daily()
                "radio"->tracks=vm.repository.radio()
                else->{
                    var next:Int?=0
                    while(next!=null) {
                        val page=vm.repository.tracks(playlist,next)
                        val combined=(tracks+page.songs).distinctBy{it.audioId}
                        next=page.nextOffset.takeIf{combined.size>tracks.size}
                        tracks=combined
                    }
                }
            }
        } catch(c:CancellationException){throw c} catch(_:Exception){failed=true} finally{loading=false}
    }
    LaunchedEffect(account.signedIn){if(!account.signedIn)onBack()}
    fun toggleSong(song:SongItem,exists:Boolean){scope.launch{if(exists)local.removeFromFavorites(song) else local.addToFavorites(song)}}
    val cover=playlist.cover ?: tracks.firstOrNull()?.coverUrl
    PlaylistModernDetailFrame(
        header=PlaylistModernDetailHeader(playlist.id,"qq:${playlist.id}",playlist.title,cover,
            stringResource(CoreR.string.qq_browse_count,tracks.size),false,LocalHasCustomBackground.current),
        search=PlaylistModernDetailSearch(showSearch,{showSearch=it},query,{query=it},input,focus,true,
            headerFocused,{headerFocused=it},dockedFocused,{dockedFocused=it}),
        listState=listState,snackbarHostState=snackbar,onBack=onBack,selectionCount=null,onExitSelection={},onLocatePlaying=null,
        normalActions={
            HapticIconButton(onClick={refresh++}){Icon(Icons.Filled.Refresh,stringResource(CoreR.string.qq_browse_refresh))}
            if(playlist.id !in listOf("daily","radio")) HapticIconButton(onClick={scope.launch{
                if(isFavorite)favoriteRepo.removeFavorite(summary.id,"qqmusic") else favoriteRepo.addFavorite(
                    id=summary.id,name=playlist.title,coverUrl=cover,trackCount=tracks.size,source="qqmusic",songs=emptyList())
            }}){Icon(if(isFavorite)Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,stringResource(CoreR.string.favorite_add))}
        },
        beforeList={QQLoadNotice(loading,failed,false,{refresh++})},
        playbackActions={PlaylistModernPlaybackActions(
            songCount=if(loading)0 else shown.size,shuffleEnabled=shuffle,repeatMode=repeat,
            onPlayInOrder={if(shown.isNotEmpty())onPlay(shown,0)},
            onShufflePlay={if(shown.isNotEmpty())onPlay(shown.shuffled(),0)},
            onToggleShuffle={PlayerManager.setShuffle(!shuffle)},onCycleRepeatMode={PlayerManager.cycleRepeatMode()},
            onExportToLocalPlaylist={showExport=true})},
        content={
            itemsIndexed(shown,key={_,s->s.audioId ?: s.id.toString()}){index,song->
                PlaylistModernListItemSurface(cover,false){SongRow(index+1,song,
                    current?.channelId==song.channelId&&current?.audioId==song.audioId,playing,lookup.contains(song),::toggleSong,
                    true,false,false,{}, {},{onPlay(shown,index)}, {PlayerManager.addToQueueNext(song)},
                    {PlayerManager.addToQueueEnd(song)},snackbar,false)}
            }
            if(shown.isEmpty()&&!loading&&!failed)item{Text(stringResource(CoreR.string.qq_browse_empty))}
        },
        overlays={if(showExport)PlaylistExportSheet(title=stringResource(CoreR.string.playlist_export_to_local),
            playlists=allPlaylists.filterNot{LocalFilesPlaylist.isSystemPlaylist(it,context)},selectedCount=shown.size,onDismissRequest={showExport=false},
            onCreateAndExport={name->scope.launch{local.createPlaylistWithSongs(name,shown);showExport=false}},
            onExportToPlaylist={p->scope.launch{local.addSongsToPlaylist(p.id,shown);showExport=false}})}
    )
}
