package moe.ouom.neriplayer.ui.screen.qqmusic

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.platform.qqmusic.QQMusicPlaylist
import moe.ouom.neriplayer.ui.navigateToQQMusicCollection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],application=Application::class)
class QQMusicCollectionNavigationTest {
    @get:Rule val composeRule=createComposeRule()
    private lateinit var controller: NavHostController

    @Test fun `albums push separate entries and back restores the same artist state`() {
        host()
        val artist=QQMusicPlaylist("artist:mid","Artist")
        val first=QQMusicPlaylist("album:first","First")
        val second=QQMusicPlaylist("album:second","Second")
        composeRule.runOnIdle { controller.navigateToQQMusicCollection(artist) }
        val artistEntry=composeRule.runOnIdle { checkNotNull(controller.currentBackStackEntry).also {
            it.savedStateHandle["selectedTab"]=1
            it.savedStateHandle["scrollOffset"]=240
        } }
        composeRule.runOnIdle { controller.navigateToQQMusicCollection(first) }
        val albumEntry=composeRule.runOnIdle { checkNotNull(controller.currentBackStackEntry) }
        assertNotSame(artistEntry,albumEntry)
        composeRule.runOnIdle { controller.navigateToQQMusicCollection(second) }
        composeRule.runOnIdle { assertTrue(controller.popBackStack()) }
        composeRule.runOnIdle { assertSame(albumEntry,controller.currentBackStackEntry) }
        composeRule.runOnIdle { assertTrue(controller.popBackStack()) }
        composeRule.runOnIdle {
            assertSame(artistEntry,controller.currentBackStackEntry)
            assertEquals(1,artistEntry.savedStateHandle.get<Int>("selectedTab"))
            assertEquals(240,artistEntry.savedStateHandle.get<Int>("scrollOffset"))
        }
    }

    @Test fun `opening the current collection again does not add or replace its entry`() {
        host()
        val artist=QQMusicPlaylist("artist:mid","Artist")
        composeRule.runOnIdle { controller.navigateToQQMusicCollection(artist) }
        val entry=composeRule.runOnIdle { controller.currentBackStackEntry }
        composeRule.runOnIdle { controller.navigateToQQMusicCollection(artist.copy(title="Updated title")) }
        composeRule.runOnIdle {
            assertSame(entry,controller.currentBackStackEntry)
            assertTrue(controller.popBackStack())
        }
        composeRule.runOnIdle { assertEquals("home",controller.currentDestination?.route) }
    }

    private fun host() {
        composeRule.setContent {
            val nav=rememberNavController()
            SideEffect { controller=nav }
            MaterialTheme {
                NavHost(nav,startDestination="home") {
                    composable("home") { Text("Home") }
                    composable("qq_playlist_detail/{playlistJson}",arguments=listOf(
                        navArgument("playlistJson") { type=NavType.StringType }
                    )) { Text("QQ collection") }
                }
            }
        }
    }
}
