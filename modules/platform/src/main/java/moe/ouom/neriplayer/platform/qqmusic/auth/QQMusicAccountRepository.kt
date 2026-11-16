@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.qqmusic.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicQuality
import moe.ouom.neriplayer.platform.qqmusic.api.parseQQCookies
import moe.ouom.neriplayer.platform.qqmusic.api.qqAccountId
import moe.ouom.neriplayer.platform.qqmusic.api.qqMusicKey

data class QQMusicAccountState(val signedIn: Boolean = false, val savedAt: Long = 0L)

/** Auth is separate from playlists/backups. Fail closed if encrypted storage cannot be opened. */
class QQMusicAccountRepository(context: Context, client: okhttp3.OkHttpClient = okhttp3.OkHttpClient()) {
    private val mutableLibraryRevision = MutableStateFlow(0)
    val libraryRevision = mutableLibraryRevision.asStateFlow()
    fun notifyLibraryChanged() { mutableLibraryRevision.update { it + 1 } }
    private val mutableQuality = MutableStateFlow(QQMusicQuality.EXHIGH)
    val qualityState = mutableQuality.asStateFlow()
    @Volatile var preferredQuality: QQMusicQuality = QQMusicQuality.EXHIGH
        private set
    private val app = context.applicationContext
    private val preferences by lazy {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, "qqmusic_accounts_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val mutableState = MutableStateFlow(QQMusicAccountState())
    val state = mutableState.asStateFlow()
    private val sessionClient = QQMusicSessionClient(client)
    private val session = QQMusicSession(::storedCookie, ::replaceCookie, { cookie ->
        sessionClient.renew(cookie, deviceId())
    })

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@QQMusicAccountRepository) {
            preferredQuality = readQuality()
            mutableQuality.value = preferredQuality
            mutableState.value = QQMusicAccountState(valid(preferences.getString("cookie", "").orEmpty()),
                preferences.getLong("savedAt", 0L))
        }
    }

    suspend fun cookie(): String = session.cookie()

    /** Only retry the exact rejected credential; a newer login may already have replaced it. */
    suspend fun renewRejectedCookie(cookie: String): String = session.cookie(cookie)

    private suspend fun storedCookie(): String = withContext(Dispatchers.IO) {
        synchronized(this@QQMusicAccountRepository) { preferences.getString("cookie", "").orEmpty() }
    }

    private suspend fun deviceId(): String = withContext(Dispatchers.IO) {
        synchronized(this@QQMusicAccountRepository) {
            preferences.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also {
                check(preferences.edit().putString("deviceId", it).commit())
            }
        }
    }

    private suspend fun replaceCookie(expected: String, updated: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@QQMusicAccountRepository) {
            if (preferences.getString("cookie", "").orEmpty() != expected) false else {
                require(valid(updated) && qqAccountId(expected) == qqAccountId(updated))
                val now = System.currentTimeMillis()
                check(preferences.edit().putString("cookie", updated).putLong("savedAt", now).commit())
                mutableState.value = QQMusicAccountState(true, now)
                true
            }
        }
    }

    suspend fun save(raw: String) = withContext(Dispatchers.IO) {
        require(raw.none { it == '\r' || it == '\n' })
        val cookie = parseQQCookies(raw).entries.joinToString("; ") { "${it.key}=${it.value}" }
        require(valid(cookie))
        synchronized(this@QQMusicAccountRepository) {
            val now = System.currentTimeMillis()
            check(preferences.edit().putString("cookie", cookie).putLong("savedAt", now).commit())
            mutableState.value = QQMusicAccountState(true, now)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(this@QQMusicAccountRepository) {
            check(preferences.edit().remove("cookie").remove("savedAt").commit())
            mutableState.value = QQMusicAccountState()
        }
    }

    suspend fun quality(): QQMusicQuality = withContext(Dispatchers.IO) {
        readQuality().also { preferredQuality = it; mutableQuality.value = it }
    }

    suspend fun setQuality(quality: QQMusicQuality) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("quality", quality.name).commit())
        preferredQuality = quality
        mutableQuality.value = quality
    }

    private fun readQuality(): QQMusicQuality = runCatching {
        QQMusicQuality.valueOf(preferences.getString("quality", "EXHIGH").orEmpty())
    }.getOrDefault(QQMusicQuality.EXHIGH)

    private fun valid(cookie: String): Boolean = qqAccountId(cookie).isNotBlank() && qqMusicKey(cookie).isNotBlank()
}
