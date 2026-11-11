package moe.ouom.neriplayer.ui.viewmodel.artist

import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary

data class ArtistDetailHeader(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val avatarUrl: String,
    val alias: String,
    val briefDesc: String,
    val musicSize: Int,
    val albumSize: Int,
    val followed: Boolean,
    val canFollow: Boolean = true
)

data class ArtistDetailUiState(
    val loading: Boolean = true,
    val followUpdating: Boolean = false,
    val error: UiText? = null,
    val header: ArtistDetailHeader? = null,
    val songs: List<SongItem> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val songsHasMore: Boolean = false,
    val albumsHasMore: Boolean = false,
    val songsLoadingMore: Boolean = false,
    val albumsLoadingMore: Boolean = false
)


// Keep the existing NetEase API while the presentation is shared by native artist pages.
typealias NeteaseArtistHeader = ArtistDetailHeader
typealias NeteaseArtistDetailUiState = ArtistDetailUiState
