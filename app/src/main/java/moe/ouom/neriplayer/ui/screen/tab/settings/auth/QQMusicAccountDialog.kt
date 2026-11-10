package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicException
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicQuality
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrLogin
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrSession
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QQMusicAccountDialog(onDismiss: () -> Unit) {
    val accounts = AppContainer.qqMusicAccounts
    val account by accounts.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val login = remember { QQMusicQrLogin(AppContainer.sharedOkHttpClient) }
    var session by remember { mutableStateOf<QQMusicQrSession?>(null) }
    var status by remember { mutableStateOf(QQMusicQrStatus.WAITING_SCAN) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var rawCookie by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(QQMusicQuality.EXHIGH) }
    var creating by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            accounts.load()
            quality = accounts.quality()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = CoreCommonR.string.qq_music_storage_failed }
    }
    LaunchedEffect(creating, account.signedIn) {
        if (account.signedIn) return@LaunchedEffect
        busy = true
        error = null
        session = null
        try {
            session = login.create()
            status = QQMusicQrStatus.WAITING_SCAN
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = CoreCommonR.string.qq_music_request_failed }
        finally { busy = false }
    }
    LaunchedEffect(session, account.signedIn) {
        val qr = session ?: return@LaunchedEffect
        if (account.signedIn) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1500)
                try {
                    val result = login.poll(qr)
                    status = result.status
                    if (result.status == QQMusicQrStatus.SUCCESS) {
                        accounts.save(requireNotNull(result.cookie))
                        session = null
                        onDismiss()
                        break
                    }
                    if (result.status == QQMusicQrStatus.EXPIRED || result.status == QQMusicQrStatus.CANCELLED) break
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: QQMusicException) {
                    error = qqMusicErrorResource(failure)
                    break
                } catch (_: Exception) {
                    error = CoreCommonR.string.qq_music_request_failed
                    break
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(CoreCommonR.string.settings_qq_music), style = MaterialTheme.typography.headlineSmall)
            if (account.signedIn) {
                Text(stringResource(CoreCommonR.string.settings_account_authorization_saved))
                TextButton(onClick = {
                    scope.launch {
                        try { accounts.clear() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = CoreCommonR.string.qq_music_storage_failed }
                    }
                }) { Text(stringResource(CoreCommonR.string.settings_saved_cookie_logout)) }
            } else {
                if (busy) CircularProgressIndicator()
                val bitmap = remember(session) { session?.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                bitmap?.let { Image(it, stringResource(CoreCommonR.string.qq_music_scan_hint), Modifier.size(216.dp)) }
                Text(stringResource(when (status) {
                    QQMusicQrStatus.WAITING_CONFIRM -> CoreCommonR.string.qq_music_confirm_hint
                    QQMusicQrStatus.EXPIRED, QQMusicQrStatus.CANCELLED -> CoreCommonR.string.qq_music_qr_expired
                    else -> CoreCommonR.string.qq_music_scan_hint
                }))
                TextButton(onClick = { creating++ }, enabled = !busy) {
                    Text(stringResource(CoreCommonR.string.qq_music_refresh_qr))
                }
                OutlinedTextField(value = rawCookie, onValueChange = { rawCookie = it },
                    label = { Text(stringResource(CoreCommonR.string.qq_music_cookie_hint)) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                TextButton(onClick = {
                    scope.launch {
                        try { accounts.save(rawCookie); rawCookie = ""; onDismiss() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: IllegalArgumentException) { error = CoreCommonR.string.qq_music_cookie_invalid }
                        catch (_: Exception) { error = CoreCommonR.string.qq_music_storage_failed }
                    }
                }, enabled = rawCookie.isNotBlank()) { Text(stringResource(CoreCommonR.string.qq_music_import_cookie)) }
            }
            Text(stringResource(CoreCommonR.string.qq_music_preferred_quality), style = MaterialTheme.typography.titleSmall)
            QQMusicQuality.entries.forEach { option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == option, onClick = {
                        scope.launch {
                            try { accounts.setQuality(option); quality = option }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = CoreCommonR.string.qq_music_storage_failed }
                        }
                    })
                    Text(stringResource(when (option) {
                        QQMusicQuality.LOSSLESS -> CoreCommonR.string.quality_lossless
                        QQMusicQuality.EXHIGH -> CoreCommonR.string.quality_very_high
                        QQMusicQuality.STANDARD -> CoreCommonR.string.quality_standard
                    }))
                }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text(stringResource(CoreCommonR.string.action_close)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal fun qqMusicErrorResource(error: QQMusicException): Int = when (error.kind) {
    QQMusicException.Kind.LOGIN_REQUIRED -> CoreCommonR.string.qq_music_login_required
    QQMusicException.Kind.NO_PLAY_URL -> CoreCommonR.string.qq_music_unavailable
    QQMusicException.Kind.VERIFICATION_REQUIRED -> CoreCommonR.string.qq_music_verification_required
    QQMusicException.Kind.REQUEST_FAILED -> CoreCommonR.string.qq_music_request_failed
}
