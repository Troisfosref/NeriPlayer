package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.platform.qqmusic.QQMusicSearchCollection
import moe.ouom.neriplayer.platform.qqmusic.QQMusicSearchType
import moe.ouom.neriplayer.ui.screen.qqmusic.LocalQQPlaylistOpen

@Composable
private fun qqSearchLabel(type: QQMusicSearchType) = stringResource(when (type) {
    QQMusicSearchType.SONG -> CoreR.string.explore_search_type_song
    QQMusicSearchType.ALBUM -> CoreR.string.qq_search_album
    QQMusicSearchType.ARTIST -> CoreR.string.explore_search_type_artist
    QQMusicSearchType.PLAYLIST -> CoreR.string.explore_search_type_playlist
})

private fun qqSearchIcon(type: QQMusicSearchType) = when (type) {
    QQMusicSearchType.SONG -> Icons.Outlined.MusicNote
    QQMusicSearchType.ALBUM -> Icons.Outlined.Album
    QQMusicSearchType.ARTIST -> Icons.Filled.AccountCircle
    QQMusicSearchType.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
}

@Composable
internal fun QQMusicSearchTypeChips(
    selected: QQMusicSearchType,
    onSelected: (QQMusicSearchType) -> Unit,
    selectedAlpha: Float,
    unselectedAlpha: Float,
    borderAlpha: Float
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("exploreQQMusicSearchTypeBar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(QQMusicSearchType.entries) { type ->
            ExploreTagChip(
                label = qqSearchLabel(type), icon = qqSearchIcon(type), selected = selected == type,
                onClick = { onSelected(type) }, selectedAlpha = selectedAlpha,
                unselectedAlpha = unselectedAlpha, borderAlpha = borderAlpha
            )
        }
    }
}

@Composable
internal fun QQMusicCollectionSearchRow(collection: QQMusicSearchCollection, offlineMode: Boolean) {
    val open = LocalQQPlaylistOpen.current
    val count = if (collection.count > 0) stringResource(CoreR.string.qq_browse_count, collection.count) else ""
    LinkedCollectionRow(
        title = collection.title,
        subtitle = listOf(collection.subtitle, count).filter(String::isNotBlank).joinToString(" · ")
            .ifBlank { qqSearchLabel(collection.type) },
        coverUrl = collection.cover, offlineMode = offlineMode,
        fallbackIcon = {
            Icon(qqSearchIcon(collection.type), null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = { open(collection.playlist()) }
    )
}
