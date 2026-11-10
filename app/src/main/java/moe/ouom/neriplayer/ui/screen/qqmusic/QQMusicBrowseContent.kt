package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.*
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight

@Composable
fun QQMusicLibraryContent(listState: LazyListState, vm: QQMusicBrowseViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<QQMusicPlaylist?>(null) }
    LaunchedEffect(account) { selected=null }
    val bottom = LocalMiniPlayerHeight.current
    LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=bottom+24.dp)) {
        if(!account.signedIn) item { Text(stringResource(CoreR.string.qq_browse_login),Modifier.padding(24.dp)) }
        else {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    profile.data?.let { p ->
                        AsyncImage(p.avatar,null,Modifier.padding(end=12.dp).size(56.dp),contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f)) { Text(p.nickname,style=MaterialTheme.typography.titleLarge);Text(p.id,style=MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick=vm::refresh) { Text(stringResource(CoreR.string.qq_browse_refresh)) }
                }
                QQLoadNotice(profile.loading,profile.failed,false,vm::refresh)
            }
            item { QQPlaylistRow(QQMusicPlaylist("favorites",stringResource(CoreR.string.qq_browse_liked),directory=201)) { selected=it } }
            item { QQHeading(stringResource(CoreR.string.qq_browse_created));QQLoadNotice(created.loading,created.failed,created.data?.isEmpty()==true,vm::refresh) }
            items(created.data.orEmpty(),key={"created:${it.id}"}) { p -> QQPlaylistRow(p) { selected=it } }
            item { QQHeading(stringResource(CoreR.string.qq_browse_collected));QQLoadNotice(collected.loading,collected.failed,collected.data?.isEmpty()==true,vm::refresh) }
            items(collected.data.orEmpty(),key={"collected:${it.id}"}) { p -> QQPlaylistRow(p) { selected=it } }
        }
    }
    selected?.let { QQMusicTracksDialog(it.title,vm,playlist=it,onDismiss={selected=null}) }
}

@Composable
fun QQMusicHomeContent(refreshKey: Int = 0, vm: QQMusicBrowseViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val daily by vm.daily.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<QQMusicPlaylist?>(null) }
    var songs by remember { mutableStateOf<Pair<String,List<SongItem>>?>(null) }
    LaunchedEffect(account) { selected=null;songs=null }
    LaunchedEffect(refreshKey) { if(refreshKey>0) vm.refresh() }
    if(!account.signedIn) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(CoreR.string.settings_qq_music),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            TextButton(onClick=vm::refresh) { Text(stringResource(CoreR.string.qq_browse_refresh)) }
        }
        val sections=listOf(stringResource(CoreR.string.qq_browse_daily) to daily,stringResource(CoreR.string.qq_browse_radio) to radio)
        sections.forEach { (title,state) ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(!state.data.isNullOrEmpty()) TextButton(onClick={songs=title to state.data.orEmpty()}) { Text(stringResource(CoreR.string.qq_browse_more)) }
            }
            QQLoadNotice(state.loading,state.failed,state.data?.isEmpty()==true,vm::refresh)
            state.data.orEmpty().take(4).forEachIndexed { index,song -> QQSongRow(song) { PlayerManager.playPlaylist(state.data.orEmpty(),index) } }
        }
        QQHeading(stringResource(CoreR.string.qq_browse_recommended))
        QQLoadNotice(recommended.loading,recommended.failed,recommended.data?.isEmpty()==true,vm::refresh)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            items(recommended.data.orEmpty(),key={it.id}) { p ->
                Column(Modifier.width(144.dp).clickable { selected=p }) {
                    AsyncImage(p.cover,null,Modifier.size(144.dp),contentScale=ContentScale.Crop)
                    Text(p.title,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp))
                }
            }
        }
    }
    selected?.let { QQMusicTracksDialog(it.title,vm,playlist=it,onDismiss={selected=null}) }
    songs?.let { (title,tracks) -> QQMusicTracksDialog(title,vm,initialSongs=tracks,onDismiss={songs=null}) }
}

@Composable
private fun QQHeading(text: String) { Text(text,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(vertical=20.dp)) }

@Composable
private fun QQLoadNotice(loading: Boolean, failed: Boolean, empty: Boolean, retry: () -> Unit) {
    when {
        loading -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        failed -> TextButton(onClick=retry) { Text(stringResource(CoreR.string.qq_browse_retry)) }
        empty -> Text(stringResource(CoreR.string.qq_browse_empty),Modifier.padding(vertical=12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QQPlaylistRow(p: QQMusicPlaylist,onClick: (QQMusicPlaylist)->Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick(p) }.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        if(p.cover!=null) AsyncImage(p.cover,null,Modifier.size(64.dp),contentScale=ContentScale.Crop)
        else Surface(Modifier.size(64.dp),color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
            Box(contentAlignment=Alignment.Center) { Text(if(p.directory==201) "♥" else "♪",style=MaterialTheme.typography.headlineMedium) }
        }
        Column(Modifier.padding(start=16.dp).weight(1f)) {
            Text(p.title,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(p.count>0) Text(stringResource(CoreR.string.qq_browse_count,p.count),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QQSongRow(song: SongItem,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        AsyncImage(song.coverUrl,null,Modifier.size(48.dp),contentScale=ContentScale.Crop)
        Column(Modifier.padding(start=12.dp).weight(1f)) {
            Text(song.name,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(song.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QQMusicTracksDialog(title: String,vm: QQMusicBrowseViewModel,playlist: QQMusicPlaylist?=null,initialSongs: List<SongItem> = emptyList(),onDismiss:()->Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    var tracks by remember(playlist) { mutableStateOf(initialSongs) }
    var next by remember(playlist) { mutableStateOf<Int?>(if(playlist!=null) 0 else null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    suspend fun load() {
        val offset=next ?: return
        if(loading || playlist==null)return
        loading=true;error=false
        try {
            val page=vm.repository.tracks(playlist,offset)
            val combined=(tracks+page.songs).distinctBy { it.audioId }
            next=page.nextOffset.takeIf { combined.size>tracks.size || offset==0 }
            tracks=combined
        } catch(c:CancellationException) { throw c } catch(_:Exception) { error=true }
        finally { loading=false }
    }
    LaunchedEffect(playlist) { load() }
    if(!account.signedIn) { LaunchedEffect(Unit) { onDismiss() };return }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onDismiss) { Text(stringResource(CoreR.string.qq_browse_back)) }
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(tracks.isNotEmpty()) TextButton(enabled=!loading,onClick={scope.launch {
                    error=false
                    while(next!=null && !error) load()
                    if(!error) { PlayerManager.playPlaylist(tracks,0);onDismiss() }
                }}) { Text(stringResource(CoreR.string.qq_browse_play_all)) }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(tracks,key={_,s->s.audioId ?: s.id.toString()}) { index,song -> QQSongRow(song) {PlayerManager.playPlaylist(tracks,index);onDismiss()} }
                    item {
                        QQLoadNotice(loading,error,tracks.isEmpty()&&!loading&&!error, { scope.launch { load() } })
                        if(next!=null && !loading&&!error) TextButton(onClick={scope.launch {load()} }) {Text(stringResource(CoreR.string.qq_browse_more))}
                    }
                }
            }
        }
    }
}
