package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.screen.nowplaying.ArtistPickerSheet

internal data class QQMusicSongNavigation(val openAlbum: () -> Unit,val openArtist: () -> Unit,val busy: Boolean)
private enum class QQSongTarget { Album, Artist }

@Composable
internal fun rememberQQMusicSongNavigation(song: SongItem?,offlineMode: Boolean,snackbar: SnackbarHostState,
    onNavigate: (QQMusicPlaylist) -> Unit = LocalQQPlaylistOpen.current): QQMusicSongNavigation {
    val mid=song?.let { QQMusicSongRef.from(it) }?.mid
    val resources=LocalResources.current
    val navigate by rememberUpdatedState(onNavigate)
    var request by remember(mid) { mutableStateOf<QQSongTarget?>(null) }
    var candidates by remember(mid) { mutableStateOf(emptyList<QQMusicPlaylist>()) }
    LaunchedEffect(mid,request) {
        val target=request ?: return@LaunchedEffect
        val source=song ?: return@LaunchedEffect
        try {
            val collections=AppContainer.qqMusicSongNavigation.resolve(source,allowNetwork=!offlineMode)
            if(target==QQSongTarget.Album) navigate(requireNotNull(collections.album))
            else when(collections.artists.size) {
                0 -> error("No QQ Music artist MID")
                1 -> navigate(collections.artists.single())
                else -> candidates=collections.artists
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { snackbar.showNeriSnackbar(resources.getString(CoreR.string.qq_navigation_failed)) }
        finally { request=null }
    }
    if(candidates.isNotEmpty()) ArtistPickerSheet(candidates,{candidates=emptyList()},{it.title}) {
        candidates=emptyList();navigate(it)
    }
    return QQMusicSongNavigation(
        openAlbum={if(mid!=null && request==null) request=QQSongTarget.Album},
        openArtist={if(mid!=null && request==null) request=QQSongTarget.Artist},busy=request!=null)
}
