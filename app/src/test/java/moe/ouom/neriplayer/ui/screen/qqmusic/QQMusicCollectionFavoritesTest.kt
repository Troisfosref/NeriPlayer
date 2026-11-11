package moe.ouom.neriplayer.ui.screen.qqmusic

import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import org.junit.Assert.*
import org.junit.Test

class QQMusicCollectionFavoritesTest {
    @Test fun `album favorites retain MID across storage and never reopen as a numeric playlist`() {
        val album = QQMusicPlaylist("album:002qyIry4Y11h1", "Thriller", "https://example.invalid/cover", 9)
        val target = requireNotNull(album.favoriteTarget())
        assertEquals(QQ_MUSIC_ALBUM_FAVORITE_SOURCE, target.source)
        assertEquals("002qyIry4Y11h1", target.browseId)
        assertEquals(target, album.copy(title = "Renamed", count = 10).favoriteTarget())
        assertNotEquals(target.id, album.copy(id = "album:003ZQMNF40Wc8H").favoriteTarget()?.id)
        val saved = FavoritePlaylist(target.id, album.title, album.cover, album.count, target.source,
            browseId = target.browseId, songs = emptyList())
        assertEquals(album, saved.toQQMusicAlbumPlaylist())
        assertNull(saved.copy(browseId = null).toQQMusicAlbumPlaylist())
        assertNull(saved.copy(browseId = "../invalid").toQQMusicAlbumPlaylist())
        assertNull(saved.copy(source = "qqmusic").toQQMusicAlbumPlaylist())
    }

    @Test fun `playlist favorite identity is preserved and artists or temporary feeds remain excluded`() {
        val playlist = requireNotNull(QQMusicPlaylist("12345", "List").favoriteTarget())
        assertEquals(12345L, playlist.id)
        assertEquals("qqmusic", playlist.source)
        assertNull(QQMusicPlaylist("artist:artistMid", "Artist").favoriteTarget())
        assertNull(QQMusicPlaylist("daily", "Daily").favoriteTarget())
        assertNull(QQMusicPlaylist("radio", "Radio").favoriteTarget())
        assertNull(QQMusicPlaylist("album:null", "Invalid").favoriteTarget())
    }
}
