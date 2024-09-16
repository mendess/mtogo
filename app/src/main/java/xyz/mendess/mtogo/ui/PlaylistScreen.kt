package xyz.mendess.mtogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mendess.mtogo.models.PlayerViewModel
import xyz.mendess.mtogo.models.Playlist
import xyz.mendess.mtogo.models.PlaylistLoadingState
import xyz.mendess.mtogo.models.PlaylistViewModel

@Composable
fun PlaylistScreen(
    playlistViewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val list by playlistViewModel.playlistFlow.collectAsState()
    when (val list = list) {
        PlaylistLoadingState.Loading ->
            Text("loading playlist....", modifier = modifier)

        is PlaylistLoadingState.Success ->
            PlaylistContent(list.playlist, playerViewModel, modifier = modifier)

        is PlaylistLoadingState.Error ->
            Text("failed to get list: $list", modifier = modifier)
    }
}

@Composable
private fun PlaylistContent(
    list: Playlist,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                colors = ButtonDefaults.buttonColors()
                    .copy(containerColor = MaterialTheme.colorScheme.secondary),
                onClick = {
                    playerViewModel.queuePlaylistItems(
                        list.songs.shuffled().asSequence()
                    )
                }) {
                Text("Shuffle all")
            }
        }
        Spacer(modifier = modifier.padding(10.dp))
        LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
            items(list.songs, key = { it.id }) { song ->
                Button(
                    onClick = { playerViewModel.queuePlaylistItems(sequenceOf(song)) },
                    modifier = modifier.padding(2.dp)
                ) {
                    Text(
                        text = song.title,
                        modifier = modifier
                            .padding(5.dp)
                            .fillParentMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

