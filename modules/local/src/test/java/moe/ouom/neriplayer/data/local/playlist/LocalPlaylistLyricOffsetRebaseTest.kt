package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistLyricOffsetRebaseTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `a new qq default offset rebases only customised qq lyric offsets`() = runTest {
        val storage = storageWith(
            playlist(1L, "Mixed", song(11L, MusicPlatform.QQ_MUSIC, 300L), song(12L, MusicPlatform.QQ_MUSIC, 0L)),
            playlist(2L, "Cloud", song(21L, MusicPlatform.CLOUD_MUSIC, 120L))
        )
        val repository = repository(storage)

        repository.rebaseLyricOffsetsForSource(MusicPlatform.QQ_MUSIC, previousDefaultOffsetMs = 100L, newDefaultOffsetMs = 250L)

        assertEquals(mapOf(1L to listOf(150L, 0L), 2L to listOf(120L)), offsets(repository))
        assertEquals(offsets(repository), offsets(repository(storage)))
        val modifiedAt = repository.playlists.value.associate { it.id to it.modifiedAt }
        assertTrue(modifiedAt.getValue(1L) > ORIGINAL_MODIFIED_AT)
        assertEquals(ORIGINAL_MODIFIED_AT, modifiedAt.getValue(2L))
    }

    @Test
    fun `nothing is rewritten when no song is customised for the source or the default is unchanged`() = runTest {
        val storage = storageWith(playlist(2L, "Cloud", song(21L, MusicPlatform.CLOUD_MUSIC, 120L)))
        val repository = repository(storage)
        val commits = storage.commitCount

        repository.rebaseLyricOffsetsForSource(MusicPlatform.QQ_MUSIC, previousDefaultOffsetMs = 100L, newDefaultOffsetMs = 250L)
        repository.rebaseLyricOffsetsForSource(MusicPlatform.CLOUD_MUSIC, previousDefaultOffsetMs = 100L, newDefaultOffsetMs = 100L)

        assertEquals(commits, storage.commitCount)
        assertEquals(mapOf(2L to listOf(120L)), offsets(repository))
    }

    @Test
    fun `native QQ customized offsets are preserved by QQ changes and excluded from cloud rebasing`() = runTest {
        val native = remoteNeteaseSong(id = 31L, name = "QQ Song").copy(
            channelId = QQMusicSongRef.CHANNEL, audioId = "catalogMid", matchedLyricSource = null,
            userLyricOffsetMs = 300L)
        val untouched = native.copy(id = 32L, audioId = "otherMid", userLyricOffsetMs = 0L)
        val storage = storageWith(playlist(3L, "Native QQ", native, untouched))
        val repository = repository(storage)
        repository.rebaseLyricOffsetsForSource(MusicPlatform.CLOUD_MUSIC, 100L, 250L)
        assertEquals(mapOf(3L to listOf(300L, 0L)), offsets(repository))
        repository.rebaseLyricOffsetsForSource(MusicPlatform.QQ_MUSIC, 100L, 250L)
        assertEquals(mapOf(3L to listOf(150L, 0L)), offsets(repository))
        assertEquals(offsets(repository), offsets(repository(storage)))
    }

    private fun song(id: Long, lyricSource: MusicPlatform, userOffsetMs: Long) =
        remoteNeteaseSong(id = id, name = "song-$id").copy(
            matchedLyricSource = lyricSource,
            userLyricOffsetMs = userOffsetMs
        )

    private fun storageWith(vararg playlists: LocalPlaylist) =
        RecordingStorage(primary = Gson().toJson(playlists.toList()))

    private fun playlist(id: Long, name: String, vararg songs: SongItem) =
        LocalPlaylist(id = id, name = name, songs = songs.toMutableList(), modifiedAt = ORIGINAL_MODIFIED_AT)

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "lyric_offsets.json"),
        storage = storage
    )

    private fun offsets(repository: LocalPlaylistRepository) =
        repository.playlists.value.associate { playlist -> playlist.id to playlist.songs.map(SongItem::userLyricOffsetMs) }

    private companion object {
        const val ORIGINAL_MODIFIED_AT = 1_000L
    }
}
