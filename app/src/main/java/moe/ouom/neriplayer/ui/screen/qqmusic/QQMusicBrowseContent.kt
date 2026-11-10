package moe.ouom.neriplayer.ui.screen.qqmusic

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
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
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import com.google.gson.Gson

internal val LocalQQPlaylistOpen = staticCompositionLocalOf<(QQMusicPlaylist) -> Unit> { {} }
internal fun qqPlaylistRoute(p: QQMusicPlaylist) = "qq_playlist_detail/${Uri.encode(Gson().toJson(p))}"
internal fun QQMusicPlaylist.summary() = PlaylistSummary(id.toLongOrNull() ?: -(directory ?: 201).toLong(),title,cover.orEmpty(),0,count)

@Composable
fun QQMusicLibraryContent(listState: LazyListState, vm: QQMusicBrowseViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    val open=LocalQQPlaylistOpen.current
    val context=LocalContext.current
    val shape=neriRoundedShape(12.dp)
    LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=LocalMiniPlayerHeight.current+24.dp)) {
        if(!account.signedIn) item { Text(stringResource(CoreR.string.qq_browse_login),Modifier.padding(24.dp)) }
        else {
            item {
                ListItem(headlineContent={Text(profile.data?.nickname ?: stringResource(CoreR.string.settings_qq_music))},
                    supportingContent={profile.data?.let {Text(it.id)}},
                    leadingContent={profile.data?.avatar?.let{AsyncImage(it,null,Modifier.size(48.dp).clip(CircleShape),contentScale=ContentScale.Crop)}},trailingContent={TextButton(onClick=vm::refresh){Text(stringResource(CoreR.string.qq_browse_refresh))}})
            }
            item {
                val p=QQMusicPlaylist("favorites",stringResource(CoreR.string.qq_browse_liked),directory=201)
                NeteasePlaylistRow(p.summary(),shape,context,{open(p)},false,supportingText=stringResource(CoreR.string.settings_qq_music))
            }
            item { SectionHeader(Icons.Outlined.Star,stringResource(CoreR.string.qq_browse_created));QQLoadNotice(created.loading,created.failed,created.data?.isEmpty()==true,vm::refresh) }
            items(created.data.orEmpty(),key={"created:${it.id}"}) { p -> NeteasePlaylistRow(p.summary(),shape,context,{open(p)},false) }
            item { SectionHeader(Icons.Outlined.Star,stringResource(CoreR.string.qq_browse_collected));QQLoadNotice(collected.loading,collected.failed,collected.data?.isEmpty()==true,vm::refresh) }
            items(collected.data.orEmpty(),key={"collected:${it.id}"}) { p -> NeteasePlaylistRow(p.summary(),shape,context,{open(p)},false) }
        }
    }
}

@Composable
internal fun QQMusicHomeContent(refreshKey: Int, favoriteSongLookup: FavoriteSongLookup,
    onFavoriteToggle: (SongItem,Boolean)->Unit,onShowSnackbar:(String)->Unit,favoriteKeys:Set<String> = emptySet(),vm:QQMusicBrowseViewModel=viewModel()) {
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
            SectionHeader(Icons.Outlined.Radar,"${stringResource(CoreR.string.settings_qq_music)} · $title")
            QQLoadNotice(state.loading,state.failed,state.data?.isEmpty()==true,vm::refresh)
            if(!state.data.isNullOrEmpty()) {
                ResponsiveSongPagerList(state.data.orEmpty(),{songs,index->PlayerManager.playPlaylist(songs,index)},favoriteSongLookup,onFavoriteToggle,onShowSnackbar,false)
                TextButton(onClick={open(QQMusicPlaylist(id,title,state.data?.firstOrNull()?.coverUrl,state.data?.size ?: 0))}){Text(stringResource(CoreR.string.qq_browse_more))}
            }
        }
        SectionHeader(Icons.Outlined.Star,"${stringResource(CoreR.string.settings_qq_music)} · ${stringResource(CoreR.string.qq_browse_recommended)}")
        QQLoadNotice(recommended.loading,recommended.failed,recommended.data?.isEmpty()==true,vm::refresh)
        recommended.data.orEmpty().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                row.forEach { p -> Box(Modifier.weight(1f)) { PlaylistCard(p.summary(),favoriteKeys.contains("qqmusic:${p.summary().id}"),{open(p)},onShowSnackbar,source="qqmusic") } }
                if(row.size==1)Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
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
