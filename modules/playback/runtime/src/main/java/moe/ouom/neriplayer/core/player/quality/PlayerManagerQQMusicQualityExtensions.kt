package moe.ouom.neriplayer.core.player.quality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

internal fun PlayerManager.observeQQMusicQualitySettings() {
    ioScope.launch {
        val accounts = PlayerDependencies.repositories
            .qqMusicRepository?.accounts ?: return@launch
        try { accounts.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@launch }
        accounts.qualityState.collect { quality ->
            val key = quality.name.lowercase()
            val previous = qqMusicPreferredQuality
            qqMusicPreferredQuality = key
            if (previous != key) scheduleQualityRefresh(
                PlaybackAudioSource.QQ_MUSIC, "qq_music_quality_changed"
            )
        }
    }
    ioScope.launch {
        settingsRepo.mobileDataQQMusicAudioQualityFlow.collect { key ->
            val previous = mobileDataQQMusicAudioQuality
            mobileDataQQMusicAudioQuality = key
            if (previous != key) scheduleQualityRefresh(PlaybackAudioSource.QQ_MUSIC, "qq_mobile_quality_changed")
        }
    }
}
