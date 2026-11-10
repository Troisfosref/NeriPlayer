package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.server.isServerSong

internal fun shouldLoadRemoteLyrics(song: SongItem): Boolean {
    return !song.isLocalSong() && !song.isServerSong()
}

fun shouldReadManagedDownloadLyrics(
    song: SongItem,
    isManagedLocalDownload: Boolean
): Boolean {
    return !song.isLocalSong() || isManagedLocalDownload
}
