package moe.ouom.neriplayer.platform.qqmusic

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicSongRef
import org.json.JSONArray
import org.json.JSONObject

internal fun mapQQMusicSongs(list: JSONArray): List<SongItem> = (0 until list.length()).mapNotNull {
    list.optJSONObject(it)?.let(::mapQQMusicSong)
}

internal fun mapQQMusicSong(json: JSONObject): SongItem? {
    val mid = json.optString("mid").ifBlank { json.optString("songmid") }
    val mediaMid = json.optJSONObject("file")?.optString("media_mid").orEmpty()
        .ifBlank { json.optString("strMediaMid") }
    val ref = runCatching { QQMusicSongRef(mid, mediaMid, json.optInt("type", json.optInt("songtype", 0))) }
        .getOrNull() ?: return null
    val singers = json.optJSONArray("singer") ?: JSONArray()
    val album = json.optJSONObject("album")
    val albumMid = album?.optString("mid").orEmpty().ifBlank { json.optString("albummid") }
    return SongItem(
        id = ref.numericId,
        name = json.optString("title").ifBlank { json.optString("name").ifBlank { json.optString("songname") } },
        artist = (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name") }.joinToString(" / "),
        album = album?.optString("name").orEmpty().ifBlank { json.optString("albumname") },
        albumId = album?.optLong("id") ?: json.optLong("albumid"),
        durationMs = json.optLong("interval") * 1000L,
        coverUrl = albumMid.takeIf(String::isNotBlank)?.let { "https://y.qq.com/music/photo_new/T002R800x800M000$it.jpg" },
        channelId = QQMusicSongRef.CHANNEL, audioId = mid, subAudioId = mediaMid,
        mediaUri = ref.mediaUri
    )
}
