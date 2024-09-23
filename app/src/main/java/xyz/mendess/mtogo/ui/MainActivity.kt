@file:Suppress("NAME_SHADOWING")

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
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mendess.mtogo.m.MPlayerController
import xyz.mendess.mtogo.m.MService
import xyz.mendess.mtogo.ui.theme.MToGoTheme
import xyz.mendess.mtogo.viewmodels.BackendViewModel
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel

class MainActivity : ComponentActivity() {
    private val playlistViewModel: PlaylistViewModel by viewModels()
    private val backendViewModel: BackendViewModel by viewModels { BackendViewModel.Factory }

    private val mplayer: MutableStateFlow<MPlayerController?> = MutableStateFlow(
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
                mplayer.value = MPlayerController(this@MainActivity.lifecycleScope, get())
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        controllerFuture?.apply(MediaController::releaseFuture)
        super.onStop()
    }
}

@Composable
fun Screen(
    playlistViewModel: PlaylistViewModel,
    mplayer: MPlayerController,
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
    mplayer: MPlayerController,
    backendViewModel: BackendViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Music", "Playlist", "Settings")
    var tabIndex by remember { mutableIntStateOf(0) }

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
            0 -> PlayerScreen(mplayer, darkTheme, modifier)
            1 -> PlaylistScreen(playlistViewModel, mplayer, modifier)
            2 -> SettingsScreen(backendViewModel, darkTheme, modifier)
        }
    }
}