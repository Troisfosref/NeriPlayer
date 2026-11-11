package moe.ouom.neriplayer.ui.screen.artist

import moe.ouom.neriplayer.ui.i18n.UiText
import android.app.Application
import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h1600dp")
class NeteaseArtistDetailContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val header = NeteaseArtistHeader(
        id = 7L,
        name = "Artist Seven",
        coverUrl = "",
        avatarUrl = "https://example.invalid/avatar.jpg",
        alias = "The Seventh",
        briefDesc = "Brief artist description",
        musicSize = 4,
        albumSize = 2,
        followed = false
    )

    private val songs = listOf(
        song(1, artist = "Singer", album = "First Album"),
        song(2, artist = " ", album = "Second Album"),
        song(3, artist = "Solo", album = "Netease"),
        song(4, artist = "", album = "")
    )

    private val albums = listOf(
        AlbumSummary(id = 11L, name = "Album Eleven", picUrl = "", size = 1),
        AlbumSummary(id = 12L, name = "Album Twelve", picUrl = "", size = 3)
    )

    private val events = mutableListOf<String>()

    @Before
    fun bindMediaHost() {
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @Test
    fun `phone layout shows header songs and albums and forwards actions`() {
        var ui by mutableStateOf(
            NeteaseArtistDetailUiState(
                loading = false,
                header = header,
                songs = songs,
                albums = albums,
                songsHasMore = true,
                albumsHasMore = true,
                albumsLoadingMore = true
            )
        )
        setContent { ui }

        composeRule.onNodeWithText("The Seventh").assertExists()
        composeRule.onNodeWithText("Brief artist description").assertExists()
        composeRule.onNodeWithText(plural(AppR.plurals.artist_song_count, 4)).assertExists()
        composeRule.onNodeWithText(plural(AppR.plurals.artist_album_count, 2)).assertExists()
        composeRule.onNodeWithText("Singer").assertExists()
        composeRule.onNodeWithText(" · First Album").assertExists()
        composeRule.onNodeWithText("Second Album").assertExists()
        composeRule.onNodeWithText("Solo").assertExists()
        composeRule.onAllNodesWithText(" · Netease").assertCountEquals(0)

        composeRule.onNodeWithText("Song 3").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_load_more)).performClick()
        assertEquals(listOf("song:3/4", "follow", "moreSongs"), events)

        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_albums)).performClick()
        composeRule.onNodeWithText("Album Twelve").assertExists()
        composeRule.onNodeWithText(plural(AppR.plurals.count_songs_format, 3)).assertExists()
        composeRule.onNodeWithText("Album Eleven").performClick()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_load_more)) and hasClickAction())
            .assertIsNotEnabled()
        assertEquals("album:11", events.last())

        ui = ui.copy(albums = emptyList(), albumsHasMore = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_albums_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).performClick()
        ui = ui.copy(songs = emptyList(), songsHasMore = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_songs_empty)).assertExists()
    }

    @Test
    fun `phone layout reports loading errors and missing headers`() {
        var ui by mutableStateOf(NeteaseArtistDetailUiState(loading = true))
        setContent { ui }

        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_follow)) and hasClickAction())
            .assertIsNotEnabled()
        composeRule.onNodeWithText(plural(AppR.plurals.artist_song_count, 0)).assertExists()

        ui = NeteaseArtistDetailUiState(loading = false, error = UiText.Literal("Network down"))
        composeRule.onNodeWithText("Network down").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_retry)).performClick()
        assertEquals(listOf("retry"), events)

        ui = NeteaseArtistDetailUiState(
            loading = false,
            error = UiText.Literal("Refresh failed"),
            header = header.copy(followed = true, briefDesc = "", alias = ""),
            followUpdating = true,
            songs = songs.take(1)
        )
        composeRule.onNodeWithText("Refresh failed").assertExists()
        composeRule.onNodeWithText("Song 1").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_followed)).assertExists()
        composeRule.onNodeWithText("The Seventh").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet layout shows the profile sidebar beside the works`() {
        var ui by mutableStateOf(
            NeteaseArtistDetailUiState(
                loading = false,
                header = header.copy(followed = true, coverUrl = "https://example.invalid/cover.jpg"),
                songs = songs.take(2),
                songsHasMore = true,
                songsLoadingMore = true
            )
        )
        setContent { ui }

        composeRule.onNodeWithText("Artist Seven").assertExists()
        composeRule.onNodeWithText("The Seventh").assertExists()
        composeRule.onNodeWithText("Brief artist description").assertExists()
        composeRule.onNode(hasText(string(CoreCommonR.string.artist_followed)) and hasClickAction())
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertExists()
        composeRule.onNodeWithText("Song 2").performClick()
        assertEquals(listOf("follow", "song:2/2"), events)

        ui = NeteaseArtistDetailUiState(loading = true, header = header.copy(alias = ""))
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_loading_content)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertDoesNotExist()
        composeRule.onNodeWithText("The Seventh").assertDoesNotExist()

        ui = NeteaseArtistDetailUiState(loading = false, error = UiText.Literal("Tablet failure"))
        composeRule.onNodeWithText("Tablet failure").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertDoesNotExist()

        ui = NeteaseArtistDetailUiState(loading = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_songs)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_songs_empty)).assertExists()
    }

    @Test
    fun `hero falls back to the avatar and hides blank aliases`() {
        assertEquals(NeteaseArtistHero(coverUrl = "", avatarUrl = "", name = null, alias = null), null.toNeteaseArtistHero())
        assertEquals(
            NeteaseArtistHero(
                coverUrl = "https://example.invalid/avatar.jpg",
                avatarUrl = "https://example.invalid/avatar.jpg",
                name = "Artist Seven",
                alias = "The Seventh"
            ),
            header.toNeteaseArtistHero()
        )
        assertEquals(
            NeteaseArtistHero(coverUrl = "https://example.invalid/cover.jpg", avatarUrl = "", name = "Artist Seven", alias = null),
            header.copy(coverUrl = "https://example.invalid/cover.jpg", avatarUrl = "", alias = " ").toNeteaseArtistHero()
        )
    }

    @Test
    fun `shared artist layout can hide unsupported follow and expand the introduction inline`() {
        setContent { NeteaseArtistDetailUiState(loading=false,header=header.copy(canFollow=false),songs=songs) }
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_follow)).assertDoesNotExist()
        composeRule.onNodeWithText(header.briefDesc).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_collapse)).assertExists().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_albums)).assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `long introduction has an explicit expand button and removes the line limit`() {
        // Real font metrics and hard paragraph breaks make this a text-layout regression test.
        val biography=(1..12).joinToString("\n") {
            "Paragraph $it: A long introduction with several words and complete sentences."
        }+"\nLAST_PARAGRAPH"
        setContent { NeteaseArtistDetailUiState(loading=false,header=header.copy(briefDesc=biography)) }
        fun layout(): TextLayoutResult {
            val results=mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(biography,useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val collapsed=layout()
        assertEquals("The phone biography must start with the three-line limit",3,collapsed.layoutInput.maxLines)
        org.junit.Assert.assertTrue("Collapsed biography must overflow: lines=${collapsed.lineCount}, " +
            "width=${collapsed.size.width}, exceeded=${collapsed.multiParagraph.didExceedMaxLines}",collapsed.hasVisualOverflow)
        composeRule.onNodeWithText(string(CoreCommonR.string.action_expand)).assertExists().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_collapse)).assertExists()
        val expanded=layout()
        assertEquals(Int.MAX_VALUE,expanded.layoutInput.maxLines)
        assertEquals(biography,expanded.layoutInput.text.text)
        org.junit.Assert.assertFalse("Expanded biography must retain all paragraphs",expanded.hasVisualOverflow)
        assertEquals("The final paragraph must remain visible in the text layout",biography.length,
            expanded.getLineEnd(expanded.lineCount-1,visibleEnd=true))
        for(line in 0 until expanded.lineCount) {
            org.junit.Assert.assertFalse("Expanded paragraph must not be ellipsized",expanded.isLineEllipsized(line))
        }
    }

    @Test
    fun `unknown album count is hidden while the actual count still renders`() {
        setContent { NeteaseArtistDetailUiState(loading=false,header=header,
            albums=listOf(AlbumSummary(1L,"Unknown count","",-1),AlbumSummary(2L,"Known count","",12))) }
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_albums)).performClick()
        composeRule.onNodeWithText("Unknown count").assertExists()
        composeRule.onNodeWithText(plural(AppR.plurals.count_songs_format,0)).assertDoesNotExist()
        composeRule.onNodeWithText(plural(AppR.plurals.count_songs_format,12)).assertExists()
    }

    @Test
    fun `album metadata is requested lazily after opening the album tab`() {
        val seen=mutableSetOf<Long>()
        setContent(onAlbumVisible={seen+=it.id}) {
            NeteaseArtistDetailUiState(loading=false,header=header,songs=songs,
                albums=(1L..64L).map { AlbumSummary(it,"Album $it","",-1) })
        }
        composeRule.runOnIdle { org.junit.Assert.assertTrue(seen.isEmpty()) }
        composeRule.onNodeWithText(string(CoreCommonR.string.artist_tab_albums)).performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertTrue(seen.isNotEmpty())
            org.junit.Assert.assertTrue("Off-screen albums must not all trigger a request",seen.size<64)
        }
    }

    private fun setContent(onAlbumVisible: (AlbumSummary) -> Unit = {}, ui: () -> NeteaseArtistDetailUiState) {
        composeRule.setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            MaterialTheme {
                ArtistDetailContent(
                    ui = ui(),
                    listState = rememberLazyListState(),
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onRetry = { events += "retry" },
                    onToggleFollow = { events += "follow" },
                    onLoadMoreSongs = { events += "moreSongs" },
                    onLoadMoreAlbums = { events += "moreAlbums" },
                    onSongClick = { list, index -> events += "song:${list[index].id}/${list.size}" },
                    onAlbumClick = { events += "album:${it.id}" },
                    offlineMode = false,
                    onAlbumVisible = onAlbumVisible
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun song(id: Long, artist: String, album: String) = SongItem(
        id = id,
        name = "Song $id",
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 61_000L,
        coverUrl = null
    )
}
