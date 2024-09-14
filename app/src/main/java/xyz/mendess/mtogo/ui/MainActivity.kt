package xyz.mendess.mtogo.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mendess.mtogo.models.PlayerViewModel
import xyz.mendess.mtogo.models.PlaylistViewModel
import xyz.mendess.mtogo.services.MService
import xyz.mendess.mtogo.ui.theme.MToGoTheme

class MainActivity : ComponentActivity() {
    private val playlistViewModel: PlaylistViewModel by viewModels()
    private val playerViewModel: MutableStateFlow<PlayerViewModel?> = MutableStateFlow(
        null
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val playerViewModel by this@MainActivity.playerViewModel.collectAsStateWithLifecycle()
            when (val vm = playerViewModel) {
                null -> {}
                vm -> Screen(playlistViewModel, vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync().apply {
            addListener({
                playerViewModel.value = PlayerViewModel(get())
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        super.onStop()
        controllerFuture?.apply(MediaController::releaseFuture)
    }
}

@Composable
fun Screen(
    playlistViewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme()
) {
    MToGoTheme(darkTheme = darkTheme) {
        Scaffold { innerPadding ->
            Column(verticalArrangement = Arrangement.Center, modifier = modifier) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = modifier.fillMaxWidth()
                ) {
                    Text("m to go", textAlign = TextAlign.Center)
                }
                TabScreen(
                    playlistViewModel,
                    playerViewModel,
                    darkTheme,
                    Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
fun TabScreen(
    playlistViewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableIntStateOf(0) }

    val tabs = listOf("Music", "Queue", "Playlist", "Categories")

    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tabIndex, modifier = modifier) {
            tabs.forEachIndexed { index, title ->
                Tab(text = { Text(title) },
                    selected = tabIndex == index,
                    onClick = { tabIndex = index }
                )
            }
        }
        when (tabIndex) {
            0 -> PlayerScreen(playerViewModel, darkTheme)
            1 -> Text(tabs[tabIndex])
            2 -> PlaylistScreen(playlistViewModel, playerViewModel, modifier)
            3 -> CategoriesScreen(playlistViewModel, playerViewModel, modifier)
        }
    }
}

//@Preview(showBackground = true)
//@Composable
//fun ScreenPreview() {
//    val playlistViewModel = PlaylistViewModel(
//        listOf("Birds of a Feather", "Signal 30", "Rex Orange County - Best Friend").map {
//            Playlist.Song(
//                title = it,
//                id = Playlist.VideoId("aksjkdlas"),
//                categories = ArrayList(),
//                duration = 50
//            )
//        }
//    )
//
//    Screen(playlistViewModel)
//}