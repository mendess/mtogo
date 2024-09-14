package xyz.mendess.mtogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mendess.mtogo.models.PlayerViewModel
import xyz.mendess.mtogo.models.Playlist
import xyz.mendess.mtogo.models.PlaylistLoadingState
import xyz.mendess.mtogo.models.PlaylistViewModel

@Composable
fun CategoriesScreen(
    playlistViewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val list by playlistViewModel.playlistFlow.collectAsState()
    when (val list = list) {
        PlaylistLoadingState.Loading ->
            Text("loading playlist....", modifier = modifier)

        is PlaylistLoadingState.Success ->
            CategoriesContent(list.playlist, playerViewModel, modifier = modifier)

        is PlaylistLoadingState.Error ->
            Text("failed to get list: $list", modifier = modifier)
    }
}

@Composable
private fun CategoriesContent(
    list: Playlist,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.Center) {
        items(list.categories) { category ->
            Button(
                onClick = {
                    playerViewModel.queuePlaylistItems(
                        category.second.asIterable().shuffled().asSequence()
                    )
                },
                modifier = modifier.padding(5.dp)
            ) {
                Text(
                    text = "${category.first} (${category.second.size})",
                    modifier = modifier
                        .padding(5.dp)
                        .fillParentMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}