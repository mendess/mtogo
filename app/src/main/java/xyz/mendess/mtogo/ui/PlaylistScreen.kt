@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mendess.mtogo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mendess.mtogo.m.MPlayerController
import xyz.mendess.mtogo.ui.util.LongPressButton
import xyz.mendess.mtogo.viewmodels.Playlist
import xyz.mendess.mtogo.viewmodels.PlaylistLoadingState
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel

@Composable
fun PlaylistScreen(
    playlistViewModel: PlaylistViewModel,
    mplayer: MPlayerController,
    modifier: Modifier = Modifier
) {
    val list by playlistViewModel.playlistFlow.collectAsState()
    when (val list = list) {
        PlaylistLoadingState.Loading ->
            Text("loading playlist....", modifier = modifier)

        is PlaylistLoadingState.Success ->
            PlaylistTabsContent(list.playlist, mplayer, modifier = modifier)

        is PlaylistLoadingState.Error ->
            Text("failed to get list: $list", modifier = modifier)
    }
}

enum class Mode {
    Songs,
    Categories;

    fun cycle(): Mode = when (this) {
        Songs -> Categories
        Categories -> Songs
    }
}

@Composable
private fun PlaylistTabsContent(
    list: Playlist,
    mplayer: MPlayerController,
    modifier: Modifier = Modifier
) {
    val lastQueue = mplayer.lastQueue.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(Mode.Songs) }
    val searchBuffer = remember { mutableStateOf("") }
    val icon = remember { mutableIntStateOf(R.drawable.baseline_shuffle_24) }
    val showClearPlaylistConfirmDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current

    val onQueue = { searchBuffer.value = "" }

    if (showClearPlaylistConfirmDialog.value) {
        AlertDialog(
            title = { Text(text = "Clear playlist?") },
            text = { Text(text = "Are you sure you want to clear?") },
            onDismissRequest = { showClearPlaylistConfirmDialog.value = false },
            confirmButton = {
                TextButton(onClick = {mplayer.clearMediaItems(); showClearPlaylistConfirmDialog.value = false}) {
                    Text("Confirm")
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val modifier = modifier
                .weight(1f)
                .padding(5.dp)
            LongPressButton(
                colors = ButtonDefaults.buttonColors()
                    .copy(containerColor = MaterialTheme.colorScheme.secondary),
                onClick = {
                    val t = context.toastQueueing()
                    mplayer.queuePlaylistItems(list.songs.shuffled().asSequence()) {
                        context.toastQueued(t)
                    }
                    icon.intValue = R.drawable.baseline_shuffle_24
                },
                onLongPress = {
                    val t = context.toastQueueing()
                    mplayer.queuePlaylistItems(list.songs.asSequence()) {
                        context.toastQueued(t)
                    }
                    icon.intValue = R.drawable.baseline_unshuffle_alt_24
                },
                modifier = modifier,
            ) {
                Icon(
                    painter = painterResource(icon.intValue),
                    contentDescription = null,
                )
            }
            when (val lastQueue = lastQueue.value) {
                null -> {
                    Button(
                        colors = ButtonDefaults.buttonColors()
                            .copy(containerColor = MaterialTheme.colorScheme.secondary),
                        onClick = { showClearPlaylistConfirmDialog.value = true },
                        modifier = modifier,
                    ) {
                        Text(text = "clear")
                    }
                }

                else -> {
                    Button(
                        colors = ButtonDefaults.buttonColors()
                            .copy(containerColor = MaterialTheme.colorScheme.secondary),
                        onClick = {
                            mplayer.scope.launch { mplayer.resetLastQueue() }
                        },
                        modifier = modifier,
                    ) {
                        val relativeLastQueue =
                            "+${lastQueue - mplayer.currentMediaItemIndex.toUInt()}"
                        Text(text = relativeLastQueue)
                    }
                }
            }
            Button(
                colors = ButtonDefaults.buttonColors()
                    .copy(containerColor = MaterialTheme.colorScheme.secondary),
                onClick = { mode = mode.cycle() },
                modifier = modifier,
            ) {
                Text(mode.cycle().toString())
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.fillMaxWidth(),
        ) {
            TextField(
                value = searchBuffer.value,
                onValueChange = { searchBuffer.value = it },
                label = { Text("search") },
                modifier = modifier.fillMaxWidth(fraction = 0.8f)
            )
        }
        when (mode) {
            Mode.Songs -> PlaylistContent(list, mplayer, searchBuffer.value, onQueue, modifier)
            Mode.Categories -> CategoriesContent(
                list,
                mplayer,
                searchBuffer.value,
                onQueue,
                modifier
            )
        }
    }
}

@Composable
private fun PlaylistContent(
    list: Playlist,
    mplayer: MPlayerController,
    filter: String,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val songs = list.songs.filter { it.name.contains(filter) }
    Column(modifier = modifier) {
        Spacer(modifier = modifier.padding(10.dp))
        LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
            if (songs.isEmpty()) {
                item(key = "search") { QueueSearchButton(mplayer, filter, onQueue, modifier) }
            } else {
                items(songs, key = { it.id }) { song ->
                    QueueButton(
                        text = song.name,
                        modifier = modifier
                    ) {
                        val t = context.toastQueueing()
                        mplayer.queuePlaylistItem(song) {
                            context.toastQueued(t)
                        }
                        onQueue()
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoriesContent(
    list: Playlist,
    mplayer: MPlayerController,
    filter: String,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = list.categories.filter { it.first.contains(filter) }
    val context = LocalContext.current
    LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.Center) {
        if (categories.isEmpty()) {
            item(key = "search") { QueueSearchButton(mplayer, filter, onQueue, modifier) }
        } else {
            items(categories) { category ->
                QueueButton(
                    text = "${category.first} (${category.second.size})",
                    modifier = modifier
                ) {
                    val t = context.toastQueueing()
                    mplayer.queuePlaylistItems(
                        category.second.asIterable().shuffled().asSequence()
                    ) {
                        context.toastQueued(t)
                    }
                    onQueue()
                }
            }
        }
    }
}

@Composable
private fun QueueButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.padding(2.dp)
    ) {
        Text(
            text = text,
            modifier = modifier
                .padding(5.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QueueSearchButton(
    mplayer: MPlayerController,
    search: String,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    QueueButton(
        text = search,
        modifier = modifier
    ) {
        mplayer.scope.launch(Dispatchers.Main) {
            mplayer.mediaItems.parcelableFromSearch(search)
                .onSuccess {
                    val t = context.toastQueueing()
                    mplayer.queueMediaItem(it, notBatching = true) {
                        context.toastQueued(t)
                    }
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        "failed to search for $search: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
        onQueue()
    }
}

fun Context.toastQueueing(): Toast =
    Toast.makeText(this, "queueing...", Toast.LENGTH_LONG).apply { show() }

suspend fun Context.toastQueued(old: Toast) {
    withContext(Dispatchers.Main) {
        old.cancel()
        Toast.makeText(this@toastQueued, "queued!", Toast.LENGTH_SHORT).show()
    }
}