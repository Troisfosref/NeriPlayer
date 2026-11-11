package moe.ouom.neriplayer.platform.qqmusic.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import moe.ouom.neriplayer.platform.qqmusic.QQMusicTrackPage

data class QQMusicCacheSnapshot<T>(val data: T, val savedAtMs: Long, val revision: Int = 0) {
    fun isFresh(now: Long, currentRevision: Int): Boolean =
        revision == currentRevision && now >= savedAtMs && now - savedAtMs < 5 * 60_000L

    fun <R> map(transform: (T) -> R) = QQMusicCacheSnapshot(transform(data), savedAtMs, revision)
}

data class QQMusicLoadState<T>(val data: T? = null, val loading: Boolean = true, val failed: Boolean = false)

/** Keep a complete snapshot visible during refresh, and never replace it with a failed/partial page. */
suspend fun <T> loadQQMusicCachedData(
    forceRefresh: Boolean,
    revision: Int,
    read: suspend () -> QQMusicCacheSnapshot<T>?,
    fetch: suspend () -> T,
    save: suspend (T) -> Unit,
    publish: (QQMusicLoadState<T>) -> Unit,
    now: () -> Long = System::currentTimeMillis
) {
    val cached = try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    currentCoroutineContext().ensureActive()
    publish(QQMusicLoadState(data = cached?.data, loading = cached == null))
    if (!forceRefresh && cached?.isFresh(now(), revision) == true) return
    val data = try { fetch() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        publish(QQMusicLoadState(data = cached?.data, loading = false, failed = true))
        return
    }
    currentCoroutineContext().ensureActive()
    publish(QQMusicLoadState(data = data, loading = false))
    // A storage failure must not turn a successful network refresh into an empty/error page.
    try { save(data) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { }
}

/** Only a completed traversal may be persisted as a whole playable collection. */
suspend fun loadQQMusicTrackPages(fetch: suspend (Int) -> QQMusicTrackPage): List<SongItem> {
    var offset = 0
    var songs = emptyList<SongItem>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = fetch(offset)
        val combined = (songs + page.songs).distinctBy { requireNotNull(QQMusicSongRef.from(it)).mid }
        val next = page.nextOffset
        check(next == null || (next > offset && combined.size > songs.size)) {
            "QQ Music pagination made no progress"
        }
        songs = combined
        if (next == null) return songs
        offset = next
    }
}
