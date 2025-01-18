@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.ui

//noinspection UsingMaterialAndMaterial3Libraries
//noinspection UsingMaterialAndMaterial3Libraries
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Divider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mendess.mtogo.R
import xyz.mendess.mtogo.m.CurrentSong
import xyz.mendess.mtogo.m.MPlayerController
import xyz.mendess.mtogo.m.daemon.PlayState
import xyz.mendess.mtogo.ui.util.AutoSizeText
import xyz.mendess.mtogo.util.identity
import xyz.mendess.mtogo.util.toInt

data class MediaButtonsVtable(
    val prev: () -> Unit = {},
    val play: () -> Unit = {},
    val pause: () -> Unit = {},
    val next: () -> Unit = {},
) {
    constructor(player: Player) : this(
        prev = player::seekToPreviousMediaItem,
        play = player::play,
        pause = player::pause,
        next = player::seekToNextMediaItem
    )
}

@Composable
fun PlayerScreen(mplayer: MPlayerController, darkTheme: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CurrentSongScreen(mplayer, darkTheme, modifier)
        Spacer(modifier.height(10.dp))
        MediaControlsScreen(mplayer, modifier)
        ProgressBarScreen(mplayer, modifier)
        CategoriesScreen(mplayer, modifier)
        NextUpScreen(mplayer, modifier)
    }
}

@Composable
private fun CurrentSongScreen(mplayer: MPlayerController, darkTheme: Boolean, modifier: Modifier) {
    val currentSong by mplayer.currentSong.collectAsStateWithLifecycle()
    CurrentSongContent(currentSong, darkTheme, modifier = modifier)
}

@Composable
private fun CurrentSongContent(
    currentSong: CurrentSong?,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxHeight(fraction = 0.4f)
            .padding(top = 5.dp),
    ) {
        Box(
            modifier = modifier
                .fillMaxHeight(fraction = 0.1f)
                .fillMaxWidth(fraction = 0.92f)
                .padding(bottom = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            AutoSizeText(
                text = currentSong?.title ?: "No song",
                modifier = modifier,
                maxLines = 1,
                fontWeight = FontWeight.ExtraBold,
                alignment = Alignment.Center,
            )
        }
        Box(
            modifier = modifier.fillMaxWidth(fraction = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            when (val thumb = currentSong?.thumbNailUri) {
                null -> {
                    val default = listOf(
                        R.drawable.default_disk_mc_11,
                        R.drawable.default_disk_mc_11_white_outline
                    )[darkTheme.toInt()]
                    Image(
                        painter = painterResource(default),
                        contentDescription = "No album art",
                        modifier = modifier,
                    )
                }

                else -> {
                    AsyncImage(
                        model = thumb.toString(),
                        contentScale = ContentScale.Crop,
                        contentDescription = "Album/Video art",
                        modifier = modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
fun MediaControlsScreen(mplayer: MPlayerController, modifier: Modifier = Modifier) {
    val playState by mplayer.playState.collectAsStateWithLifecycle()
    MediaControlsContent(playState, modifier, MediaButtonsVtable(mplayer))
}

@Composable
fun MediaControlsContent(
    playState: PlayState,
    modifier: Modifier = Modifier,
    vtable: MediaButtonsVtable,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val modifier = modifier.padding(10.dp)
        MediaButton(
            painterResource(R.drawable.baseline_skip_previous_24),
            "previous",
            modifier,
            vtable.prev,
        )

        when (playState) {
            PlayState.Playing -> MediaButton(
                painterResource(R.drawable.baseline_pause_24),
                "pause",
                modifier,
                vtable.pause,
            )

            PlayState.Paused, PlayState.Ready -> MediaButton(
                painterResource(R.drawable.baseline_play_arrow_24),
                "play",
                modifier,
                vtable.play,
            )

            PlayState.Buffering -> CircularProgressIndicator(
                modifier = modifier.size(70.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        MediaButton(
            painterResource(R.drawable.baseline_skip_next_24),
            "next",
            modifier,
            vtable.next,
        )
    }
}

@Composable
fun CategoriesScreen(mplayer: MPlayerController, modifier: Modifier = Modifier) {
    val currentSong by mplayer.currentSong.collectAsStateWithLifecycle()
    CategoriesContent(currentSong?.categories ?: emptyList(), modifier = modifier)
}

@Composable
fun CategoriesContent(categories: List<String>, modifier: Modifier = Modifier) {
    LazyRow(modifier = modifier) {
        items(categories, key = ::identity) { cat ->
            Text(
                text = cat,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Light,
                modifier = modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
fun MediaButton(
    painter: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(70.dp),
        shape = CircleShape,
        enabled = enabled,
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
fun ProgressBarScreen(mplayer: MPlayerController, modifier: Modifier = Modifier) {
    val position by mplayer.positionMs.collectAsStateWithLifecycle()
    val duration by mplayer.totalDurationMs.collectAsStateWithLifecycle()
    ProgressBarContent(position, duration, modifier = modifier)
}

@Composable
fun ProgressBarContent(
    position: Long,
    duration: Long?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth(fraction = 0.8f)
            .padding(bottom = 10.dp)
    ) {
        if (duration == null) {
            // hack to avoid UI from jumping
            Box(modifier = modifier.height(50.dp))
            Text("")
            return
        }
        val progress = position.toFloat() / duration.toFloat()
        Box(modifier = modifier.height(50.dp)) {
            Slider(value = progress, onValueChange = { }, modifier = modifier, enabled = true)
        }
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(DateUtils.formatElapsedTime(position / 1000), modifier = modifier)
            Text(DateUtils.formatElapsedTime(duration / 1000), modifier = modifier)
        }
    }
}

@Composable
fun NextUpScreen(mplayer: MPlayerController, modifier: Modifier = Modifier) {
    val nextUp by mplayer.nextUp.collectAsStateWithLifecycle()
    NextUpContent(nextUp, modifier)
}

@Composable
fun NextUpContent(list: List<String>, modifier: Modifier = Modifier) {
    Log.d("MusicPlayerScreen::NextUp", "updating up next to: $list")
    val fontWeights =
        sequenceOf(FontWeight.Normal, FontWeight.Light, FontWeight.ExtraLight)
            .plus(generateSequence { FontWeight.ExtraLight })
            .iterator()
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Divider(
            modifier = modifier
                .fillMaxWidth(fraction = 0.8f)
                .padding(vertical = 10.dp),
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
    val currentSong =
        CurrentSong(title = "No Music", thumbNailUri = null, categories = ArrayList())
    val playState = PlayState.Playing

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        CurrentSongContent(currentSong, true)
        Spacer(modifier = Modifier.height(10.dp))
        MediaControlsContent(playState, vtable = MediaButtonsVtable())
        ProgressBarContent(position = 30, duration = 500)
        CategoriesContent(currentSong.categories)
        NextUpContent(
            listOf("first", "second", "third"),
        )
    }
}