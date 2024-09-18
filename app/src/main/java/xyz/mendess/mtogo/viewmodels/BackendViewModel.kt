@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.socket.client.IO
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.ui.dataStore

data class Credentials(val uri: Uri, val token: String)

private val SAVED_STATE_DOMAIN = stringPreferencesKey("domain")
private val SAVED_STATE_TOKEN = stringPreferencesKey("token")

sealed interface StoredCredentialsState {
    data object Loading : StoredCredentialsState
    data class Loaded(val credentials: Credentials?) : StoredCredentialsState
}

typealias SocketIo = io.socket.client.Socket

class BackendViewModel(private val dataStore: DataStore<Preferences>) : ViewModel() {
    val credentials: StateFlow<StoredCredentialsState> = run {
        val domain = dataStore.data.map { preferences -> preferences[SAVED_STATE_DOMAIN] }
        val token = dataStore.data.map { preferences -> preferences[SAVED_STATE_TOKEN] }
        domain
            .zip(token) { domain, token ->
                if (domain != null && token != null) Credentials(Uri.parse(domain), token) else null
            }
            .distinctUntilChanged()
            .map(StoredCredentialsState::Loaded)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = StoredCredentialsState.Loading,
            )
    }

    val connection: StateFlow<SocketIo?> = run {
        var lastSocket: SocketIo? = null
        credentials.map {
            val credentials = when (it) {
                StoredCredentialsState.Loading -> return@map null
                is StoredCredentialsState.Loaded -> it.credentials
            }
            if (credentials == null) return@map null
            Log.d("BackendViewModel", "disconnecting from to old socket $lastSocket")
            lastSocket?.disconnect()
            Log.d("BackendViewModel", "connecting to new socket $credentials")
            lastSocket = IO.socket(credentials.uri.toString()).apply(SocketIo::connect)
            lastSocket
        }
    }.stateIn(viewModelScope, initialValue = null, started = SharingStarted.Eagerly)

    fun connect(domain: Uri, token: String) {
        viewModelScope.launch {
            Log.d("BackendViewModel", "storing to disk: $domain | $token")
            dataStore.edit { preferences ->
                preferences[SAVED_STATE_DOMAIN] = domain.toString()
                preferences[SAVED_STATE_TOKEN] = token
            }
        }
    }

    override fun onCleared() {
        connection.value?.disconnect()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BackendViewModel((this[APPLICATION_KEY] as Application).dataStore)
            }
        }
    }
}