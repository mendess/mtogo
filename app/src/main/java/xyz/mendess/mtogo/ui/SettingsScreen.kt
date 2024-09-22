@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mendess.mtogo.R
import xyz.mendess.mtogo.data.Credentials
import xyz.mendess.mtogo.data.StoredCredentialsState
import xyz.mendess.mtogo.viewmodels.BackendViewModel
import java.util.UUID

@Composable
fun SettingsScreen(viewModel: BackendViewModel, modifier: Modifier) {
    val currentCredentials by viewModel.credentials.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier.padding(top = 5.dp)) {
        (currentCredentials as? StoredCredentialsState.Loaded)?.let {
            item(key = "connect-screen") {
                ConnectScreen(it.credentials, connect = viewModel::connect, modifier)
            }
        }
    }
}

sealed interface TextFieldContent<out T> {
    data class Valid<out T>(val v: T) : TextFieldContent<T> {
        override fun toString(): String = v.toString()
    }

    data class Invalid(val badContent: String) : TextFieldContent<Nothing> {
        override fun toString(): String = badContent
    }
}

fun <T> TextFieldContent<T>?.valid(): Boolean = this is TextFieldContent.Valid

@Composable
fun ConnectScreen(
    currentCredentials: Credentials?,
    connect: (Uri, UUID) -> Unit,
    modifier: Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = modifier.fillMaxWidth()
    ) {
        val domain = remember {
            mutableStateOf<TextFieldContent<Uri>?>(
                currentCredentials?.uri?.let { TextFieldContent.Valid(it) }
            )
        }
        val token = remember {
            mutableStateOf<TextFieldContent<UUID>?>(
                currentCredentials?.token?.let { TextFieldContent.Valid(it) }
            )
        }

        Column {
            TextField(
                value = domain.value?.toString() ?: "",
                onValueChange = {
                    domain.value = try {
                        TextFieldContent.Valid(Uri.parse(it.lowercase()))
                    } catch (e: Exception) {
                        TextFieldContent.Invalid(it.lowercase())
                    }
                },
                label = { Text("domain") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                ),
                isError = !domain.value.valid()
            )
            TextField(
                value = token.value?.toString() ?: "",
                onValueChange = {
                    token.value = try {
                        if (it.length < 36) throw IllegalArgumentException()
                        TextFieldContent.Valid(UUID.fromString(it.lowercase()))
                    } catch (e: IllegalArgumentException) {
                        TextFieldContent.Invalid(it.lowercase())
                    }
                },
                label = { Text("token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None
                ),
                isError = !token.value.valid()
            )
        }
        Button(
            onClick = onClick@{
                val token = token.value as? TextFieldContent.Valid ?: return@onClick
                val domain = domain.value as? TextFieldContent.Valid ?: return@onClick
                connect(domain.v, token.v)
            },
            modifier = modifier.size(50.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(2.dp),
            enabled = token.value.valid() && domain.value.valid()
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_save_24),
                contentDescription = null,
            )
        }
    }
}
