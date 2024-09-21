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

sealed interface UriTextFieldContent {
    data class Uri(val uri: android.net.Uri) : UriTextFieldContent {
        override fun toString(): String = uri.toString()
    }

    data class Error(val badContent: String) : UriTextFieldContent {
        override fun toString(): String = badContent
    }
}

@Composable
fun ConnectScreen(
    currentCredentials: Credentials?,
    connect: (Uri, String) -> Unit,
    modifier: Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = modifier.fillMaxWidth()
    ) {
        val domain = remember {
            mutableStateOf<UriTextFieldContent?>(
                currentCredentials?.uri?.let(UriTextFieldContent::Uri)
            )
        }
        val token = remember { mutableStateOf(currentCredentials?.token) }

        Column {
            TextField(
                value = domain.value?.toString() ?: "",
                onValueChange = {
                    domain.value = try {
                        UriTextFieldContent.Uri(Uri.parse(it.lowercase()))
                    } catch (e: Exception) {
                        UriTextFieldContent.Error(it.lowercase())
                    }
                },
                label = { Text("domain") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                ),
                isError = domain.value is UriTextFieldContent.Error
            )
            TextField(
                value = token.value ?: "",
                onValueChange = { token.value = it.lowercase() },
                label = { Text("token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None
                ),
            )
        }
        Button(
            onClick = onClick@{
                val token = token.value ?: return@onClick
                val domain = domain.value as? UriTextFieldContent.Uri ?: return@onClick
                connect(domain.uri, token)
            },
            modifier = modifier.size(50.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(2.dp),
            enabled = token.value != null && domain.value is UriTextFieldContent.Uri
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_save_24),
                contentDescription = null,
            )
        }
    }
}
