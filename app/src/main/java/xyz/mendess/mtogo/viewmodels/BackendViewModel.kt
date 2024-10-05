package xyz.mendess.mtogo.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.spark.Credentials
import xyz.mendess.mtogo.util.hostname
import java.util.UUID

class BackendViewModel private constructor(
    context: Context,
    private val hostname: String,
) : ViewModel() {
    val http = HttpClient(CIO)
    private val settings = Settings(context, scope = viewModelScope)

    val credentials get() = settings.credentials

    val appVersion get() = settings.appVersion

    fun connect(domain: Uri, token: UUID) {
        settings.saveBackendConnection(domain, token)
    }

    suspend fun createNewSession(credentials: Credentials): Result<Pair<Uri, String>?> =
        runCatching {
            Log.d("BackendViewModel", "creating music session: $credentials")
            val session = http.get("${credentials.uri}/admin/music-session/$hostname") {
                bearerAuth(credentials.token.toString())
            }
            val id = Json.decodeFromString<String>(session.bodyAsText())
            Uri.parse("https://planar-bridge.mendess.xyz/music?session=$id") to id
        }

    override fun onCleared() {
        http.closeQuietly()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY] as Application)
                BackendViewModel(app, app.hostname)
            }
        }
    }
}