package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository

interface DownloadSourceServices {
    val qqMusicRepository: moe.ouom.neriplayer.platform.qqmusic.QQMusicRepository? get() = null
    val subsonicRepository: SubsonicRepository? get() = null
    val neteaseClient: NeteaseClient
    val biliClient: BiliClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
}
