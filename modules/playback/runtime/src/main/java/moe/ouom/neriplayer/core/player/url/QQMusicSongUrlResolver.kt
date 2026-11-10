package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException

internal suspend fun PlayerManager.getQQMusicAudioUrl(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects,
    suppressError: Boolean
): SongUrlResult = try {
    val result = PlayerDependencies.repositories.qqMusicRepository?.playback(song)
        ?: throw QQMusicException(QQMusicException.Kind.NO_PLAY_URL)
    result.copy(audioInfo = result.audioInfo?.let { info -> info.copy(
        qualityLabel = qualityLabelForNetease(info.qualityKey.orEmpty(), { getLocalizedString(it) })
    ) })
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val resource = when ((error as? QQMusicException)?.kind) {
        QQMusicException.Kind.LOGIN_REQUIRED -> CoreCommonR.string.qq_music_login_required
        QQMusicException.Kind.NO_PLAY_URL -> CoreCommonR.string.qq_music_unavailable
        QQMusicException.Kind.VERIFICATION_REQUIRED -> CoreCommonR.string.qq_music_verification_required
        else -> CoreCommonR.string.qq_music_request_failed
    }
    val qqCode = (error as? QQMusicException)?.diagnosticCode
        ?.takeIf { it.length in 3..8 && it.all(Char::isDigit) }
    if (!suppressError) sideEffects.emitError {
        postPlayerEvent(PlayerEvent.ShowError(
            getLocalizedString(resource) + (qqCode?.let { " (QQ $it)" } ?: "")
        ))
    }
    SongUrlResult.Failure
}
