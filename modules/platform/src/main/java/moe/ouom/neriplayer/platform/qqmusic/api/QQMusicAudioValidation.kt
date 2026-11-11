package moe.ouom.neriplayer.platform.qqmusic.api

// Full-length validation follows MusicOne's QQ audio validation policy (GPL-3.0-or-later).
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal fun qqMediaDurationMatches(actualMs: Long, expectedMs: Long): Boolean =
    expectedMs > 0 && actualMs > 0 && abs(actualMs - expectedMs) <= maxOf(2_000L, expectedMs / 50)

internal fun qqFlacDuration(header: ByteArray): Long? {
    if (header.size < 42 || String(header, 0, 4, Charsets.US_ASCII) != "fLaC" ||
        (header[4].toInt() and 0x7f) != 0 || header[5].toInt() != 0 ||
        header[6].toInt() != 0 || (header[7].toInt() and 0xff) != 34) return null
    var packed = 0L
    for (i in 18..25) packed = (packed shl 8) or (header[i].toLong() and 0xff)
    val sampleRate = packed ushr 44
    val samples = packed and 0xfffffffffL
    return if (sampleRate > 0 && samples > 0) samples * 1000 / sampleRate else null
}

internal suspend fun qqExtractFullLength(url: String, expectedMs: Long, quality: QQMusicQuality): Boolean =
    withContext(Dispatchers.IO) {
        if (expectedMs <= 0) return@withContext false
        val extractor = MediaExtractor()
        try {
            // No account cookies or credentials are forwarded to media servers.
            extractor.setDataSource(url, emptyMap())
            (0 until extractor.trackCount).any { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                mime == quality.mimeType && format.containsKey(MediaFormat.KEY_DURATION) &&
                    qqMediaDurationMatches(format.getLong(MediaFormat.KEY_DURATION) / 1000, expectedMs)
            }
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }
