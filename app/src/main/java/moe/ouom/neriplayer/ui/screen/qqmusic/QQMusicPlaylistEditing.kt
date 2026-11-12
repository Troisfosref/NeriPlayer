package moe.ouom.neriplayer.ui.screen.qqmusic

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal data class QQMusicPlaylistEditing(
    val busy: Boolean, val signedIn: Boolean, val canRemove: Boolean,
    val add: (List<SongItem>) -> Unit, val remove: (List<SongItem>) -> Unit,
    val overlays: @Composable () -> Unit
)

@Composable
internal fun rememberQQMusicPlaylistEditing(
    playlist: QQMusicPlaylist, snackbar: SnackbarHostState, onChanged: () -> Unit
): QQMusicPlaylistEditing {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts = AppContainer.qqMusicAccounts
    val account by accounts.state.collectAsStateWithLifecycle()
    val revision by accounts.libraryRevision.collectAsStateWithLifecycle()
    val editor = AppContainer.qqMusicPlaylistEditor
    var writable by remember(playlist.id, account.savedAt) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<List<SongItem>?>(null) }
    var removing by remember { mutableStateOf<List<SongItem>?>(null) }
    LaunchedEffect(playlist.id, account.savedAt, account.signedIn, revision) {
        writable = false
        if (!account.signedIn || playlist.id.startsWith("album:") || playlist.id.startsWith("artist:") ||
            playlist.id == "daily" || playlist.id == "radio") return@LaunchedEffect
        if (playlist.id == "favorites") { writable = true; return@LaunchedEffect }
        try { writable = editor.writablePlaylists().any { it.id == playlist.id } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Do not expose removal until ownership is confirmed. */ }
    }
    LaunchedEffect(account.savedAt, account.signedIn) { adding = null; removing = null }
    return QQMusicPlaylistEditing(busy, account.signedIn, writable,
        add = { if (!busy && it.isNotEmpty()) adding = it },
        remove = { if (!busy && writable && it.isNotEmpty()) removing = it },
        overlays = {
            adding?.let { songs -> QQMusicPlaylistPicker(songs,
                onDismiss = { adding = null }, onAdded = {
                    onChanged()
                    scope.launch { snackbar.showSnackbar(context.getString(CoreR.string.qq_playlist_added)) }
                }) }
            removing?.let { songs ->
                AlertDialog(
                    onDismissRequest = { if (!busy) removing = null },
                    title = { Text(stringResource(CoreR.string.qq_playlist_remove_title)) },
                    text = { Text(stringResource(CoreR.string.qq_playlist_remove_message, songs.size)) },
                    confirmButton = { HapticTextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            val message = try {
                                val result = editor.update(playlist.id, songs, add = false)
                                if (result.error == null) context.getString(CoreR.string.qq_playlist_removed)
                                else context.getString(CoreR.string.qq_playlist_partial, result.completed.size, result.total)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (e: Exception) { context.getString(qqPlaylistEditError(e)) }
                            finally { busy = false; removing = null }
                            onChanged()
                            snackbar.showSnackbar(message)
                        }
                    }) { Text(stringResource(CoreR.string.qq_playlist_remove)) } },
                    dismissButton = { HapticTextButton(enabled = !busy, onClick = { removing = null }) {
                        Text(stringResource(CoreR.string.action_cancel))
                    } }
                )
            }
        })
}

@Composable
internal fun QQMusicPlaylistEditActions(editing: QQMusicPlaylistEditing, selected: List<SongItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HapticIconButton(onClick = { expanded = true }, enabled = selected.isNotEmpty() && !editing.busy) {
            Icon(Icons.Filled.MoreVert, stringResource(CoreR.string.cd_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(CoreR.string.qq_playlist_add)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                enabled = editing.signedIn,
                onClick = { expanded = false; editing.add(selected) })
            if (editing.canRemove) DropdownMenuItem(text = { Text(stringResource(CoreR.string.qq_playlist_remove)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = { expanded = false; editing.remove(selected) })
        }
    }
}

@Composable
internal fun QQMusicSongEditMenu(editing: QQMusicPlaylistEditing, song: SongItem, close: () -> Unit,
                                 select: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(CoreR.string.qq_playlist_add)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
        enabled = editing.signedIn && !editing.busy,
        onClick = { close(); editing.add(listOf(song)) })
    if (editing.canRemove) DropdownMenuItem(text = { Text(stringResource(CoreR.string.qq_playlist_remove)) },
        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, enabled = !editing.busy,
        onClick = { close(); editing.remove(listOf(song)) })
    DropdownMenuItem(text = { Text(stringResource(CoreR.string.qq_playlist_select)) },
        onClick = { close(); select() })
}
