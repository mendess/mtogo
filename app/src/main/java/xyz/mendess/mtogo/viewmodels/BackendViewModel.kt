@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.ui.dataStore
import java.util.UUID

class BackendViewModel(
    private val settings: Settings,
) : ViewModel() {

    val credentials get() = settings.credentials

    fun connect(domain: Uri, token: UUID) {
        settings.saveBackendConnection(domain, token)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY] as Application)
                BackendViewModel(Settings(app.dataStore))
            }
        }
    }
}