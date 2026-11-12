package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/** Same native dialog, spacing and rows as the NetEase remote playlist picker. */
@Composable
internal fun QQMusicPlaylistPicker(songs: List<SongItem>, onDismiss: () -> Unit, onAdded: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = AppContainer.qqMusicPlaylistEditor
    val account by AppContainer.qqMusicAccounts.state.collectAsStateWithLifecycle()
    val initialSession = remember { account.savedAt }
    LaunchedEffect(account.savedAt, account.signedIn) {
        if (account.savedAt != initialSession) onDismiss()
    }
    var playlists by remember { mutableStateOf(listOf(QQMusicPlaylist("favorites", "", directory = 201))) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var available by remember(songs, account.savedAt) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(request, songs, account.savedAt) {
        loading = true; error = null; available = emptyMap()
        try { playlists = editor.writablePlaylists() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = context.getString(qqPlaylistEditError(e)) }
        finally { loading = false }
        val gate = Semaphore(3)
        coroutineScope {
            playlists.forEach { playlist -> launch {
                gate.withPermit {
                    try {
                        val contains = editor.containsSelection(playlist, songs)
                        available = available + (playlist.id to !contains)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { error = context.getString(qqPlaylistEditError(e)) }
                }
            } }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreR.string.qq_playlist_picker)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading || busy) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (busy) CoreR.string.qq_playlist_updating else CoreR.string.qq_playlist_loading))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val enabled = available[playlist.id] == true && !busy && !loading
                        Row(Modifier.fillMaxWidth().clip(neriRoundedShape(12.dp))
                            .alpha(if (enabled) 1f else 0.38f)
                            .clickable(enabled = enabled) {
                                scope.launch {
                                    busy = true; error = null
                                    try {
                                        val result = editor.update(playlist.id, songs, add = true)
                                        val failure = result.error
                                        if (failure == null) { onAdded(); onDismiss() }
                                        else error = if (failure.kind == QQMusicException.Kind.LOGIN_REQUIRED)
                                            context.getString(CoreR.string.qq_music_login_required)
                                        else context.getString(CoreR.string.qq_playlist_partial, result.completed.size, result.total)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (e: Exception) { error = context.getString(qqPlaylistEditError(e)) }
                                    finally { busy = false }
                                }
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null)
                            Column {
                                Text(if (playlist.id == "favorites") stringResource(CoreR.string.qq_browse_liked) else playlist.title,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (playlist.id != "favorites") Text(stringResource(CoreR.string.qq_browse_count, playlist.count),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (error != null && !busy) HapticTextButton(onClick = { request++ }) {
            Text(stringResource(CoreR.string.qq_playlist_retry))
        } },
        dismissButton = { HapticTextButton(onClick = onDismiss, enabled = !busy) {
            Text(stringResource(CoreR.string.action_cancel))
        } }
    )
}

internal fun qqPlaylistEditError(error: Exception): Int =
    if ((error as? QQMusicException)?.kind == QQMusicException.Kind.LOGIN_REQUIRED)
        CoreR.string.qq_music_login_required else CoreR.string.qq_action_failed
