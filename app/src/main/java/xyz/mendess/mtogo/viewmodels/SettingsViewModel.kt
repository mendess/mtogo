package xyz.mendess.mtogo.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import m_to_go.app.BuildConfig
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.data.CachedMusic
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.spark.Credentials
import xyz.mendess.mtogo.util.hostname
import kotlin.time.Duration.Companion.minutes


@JvmInline
value class KiloBytes(private val kb: ULong) {
    private companion object {
        const val ONE_MB = 1024UL
        val ONE_GB = ONE_MB * 1024UL
    }

    fun format(): String {
        return if (kb < ONE_MB) {
            "$kb Kb"
        } else if (kb < ONE_GB) {
            val mb = kb / ONE_MB
            val kb = kb % ONE_MB
            "$mb.${kb / 10UL} Mb"
        } else {
            val gb = kb / ONE_GB
            val mb = kb % ONE_GB / ONE_MB
            "$gb.${mb / 10UL} Gb"
        }
    }
}

fun KiloBytes?.orZero() = this ?: KiloBytes(0UL)

class SettingsViewModel private constructor(
    context: Context,
    private val hostname: String,
) : ViewModel() {
    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    val settings = Settings(context, scope = viewModelScope)

    val credentials get() = settings.credentials

    val appVersion get() = settings.appVersion

    val cachedMusicDirectorySize: StateFlow<KiloBytes?> = settings
        .cacheMusicDir
        .mapNotNull { it.uri }
        .combine(flow { emit(Unit); delay(1.minutes) }) { dir, _ ->
            withContext(Dispatchers.IO) {
                val readDir = DocumentFile.fromTreeUri(context, dir) ?: return@withContext null
                KiloBytes(
                    readDir
                        .listFiles()
                        .asSequence()
                        .filter { it.isFile }
                        .filter { it.name?.contains(CachedMusic.cachedFileNameRegex) == true }
                        .sumOf { it.length().toULong() }
                            / 1000UL
                )
            }
        }
        .stateIn(viewModelScope, initialValue = null, started = SharingStarted.Eagerly)

    val newVersionUrl = "${BuildConfig.MUSIC_BACKEND}/playlist/mtogo/download".toUri()

    val newVersionAvailableState: StateFlow<String> get() = _newVersionAvailableState
    private val _newVersionAvailableState: MutableStateFlow<String> = MutableStateFlow(appVersion)

    init {
        viewModelScope.launch {
            _newVersionAvailableState.value = runCatching {
                @Serializable
                data class Version(val version: String)

                val version =
                    http.get("${BuildConfig.MUSIC_BACKEND}/playlist/mtogo/version").body<Version>()

                version.version
            }.getOrElse {
                Log.e("Mtogo.SettingsViewModel", "failed to get latest version info: $it")
                appVersion
            }
        }
    }

    suspend fun newMusicSession(credentials: Credentials): Result<Pair<Uri, String>?> =
        runCatching {
            val session = http.get("${credentials.uri}/admin/music-session/$hostname") {
                bearerAuth(credentials.token.toString())
            }
            val id = Json.decodeFromString<String>(session.bodyAsText())
            "https://planar-bridge.mendess.xyz/music?session=$id".toUri() to id
        }

    override fun onCleared() {
        http.closeQuietly()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY] as Application)
                SettingsViewModel(app, app.hostname)
            }
        }
    }
}