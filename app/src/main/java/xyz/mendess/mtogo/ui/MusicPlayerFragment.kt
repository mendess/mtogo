package xyz.mendess.mtogo.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Divider
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mendess.mtogo.R
import xyz.mendess.mtogo.models.CurrentSong
import xyz.mendess.mtogo.models.PlayState
import xyz.mendess.mtogo.models.PlayerViewModel

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val playState by viewModel.playState.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val duration by viewModel.totalDurationMs.collectAsStateWithLifecycle()
    val nextUp by viewModel.nextUp.collectAsStateWithLifecycle()

    val progress = duration?.let { (position.toFloat() / it.toFloat()) }
    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CurrentSongScreen(
            currentSong,
            modifier,
        )
        MediaControls(
            playState,
            modifier = modifier,
            prev = viewModel.player::seekToPreviousMediaItem,
            play = viewModel.player::play,
            pause = viewModel.player::pause,
            next = viewModel.player::seekToNextMediaItem
        )
        ProgressBar(progress, position, duration, modifier = modifier)
        NextUp(nextUp, modifier = modifier)
    }
}

@Composable
fun CurrentSongScreen(currentSong: CurrentSong?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight(fraction = 0.5f)
            .padding(vertical = 50.dp)
    ) {
        Box(
            modifier = modifier
                .fillMaxHeight(fraction = 0.7f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when (val thumb = currentSong?.thumbNailUri) {
                null -> Image(
                    painter = painterResource(R.drawable.default_disk_mc_11_white_outline),
                    contentDescription = "No album art"
                )

                else -> AsyncImage(
                    model = thumb.toString(),
                    contentDescription = "Album/Video art"
                )
            }
        }
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = currentSong?.title ?: "No song",
                modifier = modifier,
                textAlign = TextAlign.Center,
                fontSize = 4.em,
                softWrap = true,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun MediaControls(
    playState: PlayState,
    modifier: Modifier = Modifier,
    prev: () -> Unit,
    play: () -> Unit,
    pause: () -> Unit,
    next: () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val modifier = modifier.padding(10.dp)
        MediaButton(
            painterResource(R.drawable.baseline_skip_previous_24),
            "previous",
            modifier,
            prev,
        )

        when (playState) {
            PlayState.Playing -> MediaButton(
                painterResource(R.drawable.baseline_pause_24),
                "pause",
                modifier,
                pause,
            )

            PlayState.Paused -> MediaButton(
                painterResource(R.drawable.baseline_play_arrow_24),
                "play",
                modifier,
                play,
            )
        }

        MediaButton(
            painterResource(R.drawable.baseline_skip_next_24),
            "next",
            modifier,
            next,
        )
    }
}

@Composable
fun MediaButton(
    painter: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(70.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(2.dp),
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(60.dp)
        )
    }
}

@Composable
fun ProgressBar(progress: Float?, position: Long, duration: Long?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth(fraction = 0.8f)
            .padding(bottom = 10.dp)
    ) {
        Box(
            modifier = modifier.height(50.dp)
        ) {
            progress?.let {
                Slider(
                    value = it,
                    onValueChange = { },
                    modifier = modifier,
                    enabled = true,
                )
            }
        }
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                DateUtils.formatElapsedTime(position / 1000),
                modifier = modifier
            )
            Text(
                duration?.let { DateUtils.formatElapsedTime(it / 1000) } ?: "??:??:??",
                modifier = modifier
            )
        }
    }
}

@Composable
fun NextUp(list: List<String>, modifier: Modifier = Modifier) {
    val fontWeights =
        sequenceOf(FontWeight.Normal, FontWeight.Light, FontWeight.ExtraLight)
            .plus(generateSequence { FontWeight.ExtraLight })
            .iterator()
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Divider(
            modifier = modifier.fillMaxWidth(fraction = 0.8f).padding(vertical = 10.dp),
            thickness = 5.dp,
            color = MaterialTheme.colorScheme.secondary
        )
        for (item in list) {
            Text(
                text = item,
                modifier = modifier.padding(10.dp),
                textAlign = TextAlign.Center,
                fontSize = 4.em,
                softWrap = true,
                fontWeight = fontWeights.next()
            )
        }
    }
}

@Preview
@Composable
fun PreviewPlayerScreen() {
    fun noop() {}
    val currentSong =
        CurrentSong(title = "No Music", thumbNailUri = null, categories = ArrayList())
    val playState = PlayState.Playing
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CurrentSongScreen(
            currentSong,
        )
        MediaControls(
            playState,
            prev = ::noop,
            play = ::noop,
            pause = ::noop,
            next = ::noop,
        )
        ProgressBar(progress = 0.3f, position = 30, duration = 500)
        NextUp(listOf("first", "second", "third"))
    }
}