package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.platform.qqmusic.QQMusicSearchCollection
import moe.ouom.neriplayer.platform.qqmusic.QQMusicSearchType
import moe.ouom.neriplayer.ui.screen.tab.explore.exploreSearchScrollContextKey
import moe.ouom.neriplayer.ui.screen.tab.explore.exploreSearchTypeBarSource
import moe.ouom.neriplayer.ui.screen.tab.explore.isExploreSearchTypeBarSourceSwap
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.tab.mergeExploreSearchResults
import org.junit.Assert.*
import org.junit.Test

class QQMusicSearchIntegrationTest {
    @Test fun `QQ native search category changes are independent scroll contexts`() {
        val keys = QQMusicSearchType.entries.map { type ->
            exploreSearchScrollContextKey(" demo ", SearchSource.QQ_MUSIC, NeteaseExploreSearchType.SONG,
                qqMusicSearchType = type)
        }
        assertEquals(4, keys.toSet().size)
        assertEquals("QQ_MUSIC|ALBUM|demo", keys[1])
        assertEquals(SearchSource.QQ_MUSIC, exploreSearchTypeBarSource(SearchSource.QQ_MUSIC, false))
        assertNull(exploreSearchTypeBarSource(SearchSource.QQ_MUSIC, true))
        assertTrue(isExploreSearchTypeBarSourceSwap(SearchSource.NETEASE, SearchSource.QQ_MUSIC))
    }

    @Test fun `pagination deduplicates the same collection without collapsing different QQ entity types`() {
        val album = ExploreSearchResult.QQCollection(QQMusicSearchCollection("same", "Album", QQMusicSearchType.ALBUM, null))
        val artist = ExploreSearchResult.QQCollection(QQMusicSearchCollection("same", "Artist", QQMusicSearchType.ARTIST, null))
        val playlist = ExploreSearchResult.QQCollection(QQMusicSearchCollection("same", "List", QQMusicSearchType.PLAYLIST, null))
        assertEquals(listOf(album, artist, playlist), mergeExploreSearchResults(listOf(album), listOf(album, artist, playlist)))
    }
}
