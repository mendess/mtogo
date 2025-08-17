package xyz.mendess.mtogo.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

data class Error(val id: Int, val message: String, val stacktrace: List<String>)

private val ID_GEN = AtomicInteger(0)

class ErrorsViewModel : ViewModel() {
    private val _errorLog = MutableStateFlow(listOf<Error>())

    val errorLog get() = _errorLog.asStateFlow()

    fun push(message: String, stacktrace: List<String>) {
        _errorLog.value += Error(ID_GEN.getAndIncrement(), message, stacktrace)
    }
}