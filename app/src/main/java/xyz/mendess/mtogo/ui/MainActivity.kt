@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.ui

import android.content.ComponentName
import android.content.Context
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mendess.mtogo.services.MService
import xyz.mendess.mtogo.ui.theme.MToGoTheme
import xyz.mendess.mtogo.util.MPlayer
import xyz.mendess.mtogo.viewmodels.BackendViewModel
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private val playlistViewModel: PlaylistViewModel by viewModels()
    private val backendViewModel: BackendViewModel by viewModels { BackendViewModel.Factory }

    private val mplayer: MutableStateFlow<MPlayer?> = MutableStateFlow(
        null
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val playerViewModel by this@MainActivity.mplayer.collectAsStateWithLifecycle()
            when (val playerViewModel = playerViewModel) {
                null -> {}
                playerViewModel -> Screen(playlistViewModel, playerViewModel, backendViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync().apply {
            addListener({
                mplayer.value = MPlayer(this@MainActivity.lifecycleScope, get())
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        mplayer.value?.close()
        controllerFuture?.apply(MediaController::releaseFuture)
        super.onStop()
    }
}

@Composable
fun Screen(
    playlistViewModel: PlaylistViewModel,
    mplayer: MPlayer,
    backendViewModel: BackendViewModel,
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
                    mplayer,
                    backendViewModel,
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
    MPlayer: MPlayer,
    backendViewModel: BackendViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableIntStateOf(3) }

    val tabs = listOf("Music", "Queue", "Playlist", "Settings")

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tabIndex, modifier = modifier) {
            tabs.forEachIndexed { index, title ->
                Tab(text = { Text(title) },
                    selected = tabIndex == index,
                    onClick = { tabIndex = index }
                )
            }
        }
        when (tabIndex) {
            0 -> PlayerScreen(MPlayer, darkTheme, modifier)
            1 -> Text(tabs[tabIndex])
            2 -> PlaylistScreen(playlistViewModel, MPlayer, modifier)
            3 -> SettingsScreen(backendViewModel, modifier)
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