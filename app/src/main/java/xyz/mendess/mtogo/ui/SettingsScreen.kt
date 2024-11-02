@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mendess.mtogo.R
import qrcode.QRCode
import xyz.mendess.mtogo.data.CacheMode
import xyz.mendess.mtogo.data.StoredCredentialsState
import xyz.mendess.mtogo.spark.Credentials
import xyz.mendess.mtogo.viewmodels.SettingsViewModel
import xyz.mendess.mtogo.viewmodels.orZero
import java.util.UUID


@Composable
fun SettingsScreen(viewModel: SettingsViewModel, darkTheme: Boolean, modifier: Modifier) {
    val currentCredentials by viewModel.credentials.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "version: ${viewModel.appVersion}",
                    fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    )
    { innerPadding ->
        LazyColumn(
            modifier = modifier
                .consumeWindowInsets(innerPadding)
                .padding(top = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            (currentCredentials as? StoredCredentialsState.Loaded)?.let {
                item(key = "connect-screen") {
                    ConnectScreen(
                        it.credentials,
                        connect = viewModel.settings::saveBackendConnection,
                        modifier
                    )
                }
                if (it.credentials != null) {
                    item(key = "session-qr") {
                        Divider(modifier)
                        MusicSessionQRScreen(it.credentials, viewModel, darkTheme, modifier)
                    }
                }
            }
            item(key = "spacer1") { Divider(modifier) }
            item(key = "storage-switch") { StorageSwitch(viewModel, modifier) }
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

sealed interface QrState {
    data object Loading : QrState
    data class Loaded(val uri: Uri, val id: String) : QrState
}

@Composable
private fun MusicSessionQRScreen(
    credentials: Credentials,
    viewmodel: SettingsViewModel,
    darkTheme: Boolean,
    modifier: Modifier
) {
    val qr = remember { mutableStateOf<QrState>(QrState.Loading) }
    LaunchedEffect("create-music-session") {
        viewmodel.newMusicSession(credentials).onSuccess {
            it?.let { (uri, id) -> qr.value = QrState.Loaded(uri, id) }
        }.onFailure {
            Log.d("SettingsScreen", "failed to create music session: $it")
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Text("session id")
        when (val state = qr.value) {
            QrState.Loading -> {
                CircularProgressIndicator(
                    modifier = modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(100.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            is QrState.Loaded -> {
                Text(state.id)
                val helloWorld = QRCode.ofSquares()
                    .withColor(qrcode.color.Colors.BLACK) // Default is Colors.BLACK
                    .withBackgroundColor(if (darkTheme) qrcode.color.Colors.WHITE else qrcode.color.Colors.TRANSPARENT)
                    .withSize(50) // Default is 25
                    .withInnerSpacing(0)
                    .build(state.uri.toString())
                Image(
                    bitmap = (helloWorld.render().nativeImage() as Bitmap).asImageBitmap(),
                    contentDescription = state.uri.toString(),
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
    }
}

@Composable
private fun Divider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = 5.dp,
        modifier = modifier
            .padding(vertical = 10.dp)
            .fillMaxWidth(fraction = 0.8f)
    )
}

@Composable
private fun StorageSwitch(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val cacheMusicDir by viewModel.settings.cacheMusicDir.collectAsStateWithLifecycle()
    val cacheMusicDirSize by viewModel.cachedMusicDirectorySize.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val isDropDownExpanded = remember { mutableStateOf(false) }
    val itemPosition = remember {
        mutableIntStateOf(cacheMusicDir.mode.ordinal)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) {
            // take persistable Uri Permission for future use
            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            viewModel.settings.enableMusicCache(it, CacheMode.entries[itemPosition.intValue])
        } else {
            viewModel.settings.disableMusicCache()
            itemPosition.intValue = CacheMode.Disabled.ordinal
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Text("cache playlist files")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = modifier) {
            Row(modifier = modifier.clickable { isDropDownExpanded.value = true }) {
                Text(CacheMode.entries[itemPosition.intValue].name)
            }
            DropdownMenu(
                expanded = isDropDownExpanded.value,
                onDismissRequest = { isDropDownExpanded.value = false },
                modifier = modifier,
            ) {
                CacheMode.entries.forEachIndexed { index, mode ->
                    Log.d("StorageScreen", "rendering: $mode")
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        onClick = {
                            itemPosition.intValue = index
                            isDropDownExpanded.value = false
                            when (CacheMode.entries[index]) {
                                CacheMode.Full -> launcher.launch(null)
                                CacheMode.MusicOnly -> launcher.launch(null)
                                CacheMode.Disabled -> viewModel.settings.disableMusicCache()
                            }
                        }
                    )
                }
            }
        }
        Column {
            Text(cacheMusicDir.uri?.path?.split(':')?.get(1) ?: "None")
            Text(cacheMusicDirSize.orZero().format())
        }
    }
}