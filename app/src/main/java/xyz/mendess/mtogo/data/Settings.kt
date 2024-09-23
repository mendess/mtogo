@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.data


import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.spark.Credentials
import java.util.UUID

private val SAVED_STATE_DOMAIN = stringPreferencesKey("domain")
private val SAVED_STATE_TOKEN = stringPreferencesKey("token")

sealed interface StoredCredentialsState {
    data object Loading : StoredCredentialsState
    data class Loaded(val credentials: Credentials?) : StoredCredentialsState
}

class Settings(private val dataStore: DataStore<Preferences>) : ViewModel() {

    val credentials: StateFlow<StoredCredentialsState> = run {
        val domain = dataStore.data.map { preferences -> preferences[SAVED_STATE_DOMAIN] }
        val token = dataStore.data.map { preferences -> preferences[SAVED_STATE_TOKEN] }
            .map { it?.let(UUID::fromString) }
        try {
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
        } catch (e: IllegalArgumentException) {
            Log.d("Settings", "deleting uuid")
            GlobalScope.launch {
                dataStore.edit { preferences -> preferences.remove(SAVED_STATE_TOKEN) }
            }
            throw e
        }
    }

    fun saveBackendConnection(domain: Uri, token: UUID) {
        viewModelScope.launch {
            Log.d("Settings", "storing to disk: $domain | $token")
            dataStore.edit { preferences ->
                preferences[SAVED_STATE_DOMAIN] = domain.toString()
                preferences[SAVED_STATE_TOKEN] = token.toString()
            }
        }
    }
}
