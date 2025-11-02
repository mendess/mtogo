@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.data


import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.spark.Credentials
import xyz.mendess.mtogo.util.appVersion
import xyz.mendess.mtogo.util.dataStore
import java.util.UUID

private val SAVED_STATE_DOMAIN = stringPreferencesKey("domain")
private val SAVED_STATE_TOKEN = stringPreferencesKey("token")
private val SAVED_STATE_MUSIC_CACHE_DIR = stringPreferencesKey("music-cache")
private val SAVED_STATE_MUSIC_CACHE_DIR_MODE = stringPreferencesKey("music-cache-mode")

sealed interface StoredCredentialsState {
    data object Loading : StoredCredentialsState
    data class Loaded(val credentials: Credentials?) : StoredCredentialsState
}

enum class CacheMode { Full, MusicOnly, Disabled }

sealed interface CacheModeSettings {
    data object Disabled : CacheModeSettings {
        override val uri get() = null
        override val mode get() = CacheMode.Disabled
    }

    data class Full(override val uri: Uri) : CacheModeSettings {
        override val mode get() = CacheMode.Full
    }

    data class MusicOnly(override val uri: Uri) : CacheModeSettings {
        override val mode get() = CacheMode.MusicOnly
    }

    val uri: Uri?
    val mode: CacheMode
}

class Settings(context: Context, private val scope: CoroutineScope) {

    private val dataStore: DataStore<Preferences> = context.dataStore
    val appVersion: String = context.appVersion

    val credentials: StateFlow<StoredCredentialsState> = run {
        val domain = dataStore.data.map { preferences -> preferences[SAVED_STATE_DOMAIN] }
        val token = dataStore.data.map { preferences -> preferences[SAVED_STATE_TOKEN] }
            .map { it?.let(UUID::fromString) }
        try {
            domain
                .zip(token) { domain, token ->
                    if (domain != null && token != null) Credentials(
                        domain.toUri(),
                        token
                    ) else null
                }
                .distinctUntilChanged()
                .map(StoredCredentialsState::Loaded)
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = StoredCredentialsState.Loading,
                )
        } catch (e: IllegalArgumentException) {
            Log.d("Settings", "deleting uuid")
            scope.launch {
                dataStore.edit { preferences -> preferences.remove(SAVED_STATE_TOKEN) }
            }
            throw e
        }
    }

    val cacheMusicDir: StateFlow<CacheModeSettings> = dataStore
        .data
        .map { preferences ->
            val url = preferences[SAVED_STATE_MUSIC_CACHE_DIR]
                ?.let(Uri::parse)
                ?: return@map CacheModeSettings.Disabled
            when (preferences[SAVED_STATE_MUSIC_CACHE_DIR_MODE]?.let(CacheMode::valueOf)) {
                null -> CacheModeSettings.Full(url)
                CacheMode.Full -> CacheModeSettings.Full(url)
                CacheMode.Disabled -> CacheModeSettings.Disabled
                CacheMode.MusicOnly -> CacheModeSettings.MusicOnly(url)
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = CacheModeSettings.Disabled
        )

    fun saveBackendConnection(domain: Uri, token: UUID) {
        scope.launch {
            Log.d("Settings", "storing to disk: $domain | $token")
            dataStore.edit { preferences ->
                preferences[SAVED_STATE_DOMAIN] = domain.toString()
                preferences[SAVED_STATE_TOKEN] = token.toString()
            }
        }
    }

    fun disableMusicCache() {
        setCacheMode(null)
    }

    fun enableMusicCache(uri: Uri, mode: CacheMode) {
        assert(mode != CacheMode.Disabled)
        setCacheMode(uri, full = mode == CacheMode.Full)
    }

    private fun setCacheMode(uri: Uri?, full: Boolean = true) {
        scope.launch {
            dataStore.edit { preferences ->
                Log.d("Settings", "storing cache folder to disk: $uri | full=$full")
                if (uri == null) {
                    preferences.remove(SAVED_STATE_MUSIC_CACHE_DIR)
                } else {
                    preferences[SAVED_STATE_MUSIC_CACHE_DIR] = uri.toString()
                    preferences[SAVED_STATE_MUSIC_CACHE_DIR_MODE] = if (full) {
                        CacheMode.Full.name
                    } else {
                        CacheMode.MusicOnly.name
                    }
                }
            }
        }
    }
}
