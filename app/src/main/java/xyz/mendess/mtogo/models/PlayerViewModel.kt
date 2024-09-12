package xyz.mendess.mtogo.models

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val MUSIC_METADATA_CATEGORIES = "cat"
private const val MUSIC_METADATA_THUMBNAIL_ID = "thumb"
private const val MUSIC_METADATA_DURATION = "dur"

class PlayerViewModel(val player: Player) : ViewModel() {
    private val _currentSong: MutableStateFlow<CurrentSong?> = MutableStateFlow(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playState = MutableStateFlow(PlayState.Paused)
    val playState = _playState.asStateFlow()

    private val _positionMs = MutableStateFlow<Long>(0)
    val positionMs = _positionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow<Long?>(null)
    val totalDurationMs = _totalDurationMs.asStateFlow()

    private val _nextUp = MutableStateFlow<List<String>>(ArrayList())
    val nextUp = _nextUp.asStateFlow()

    init {
        player.prepare()
        player.addListener(Listener(this))
        viewModelScope.launch {
            while (true) {
                _totalDurationMs.value = when (val duration = player.duration) {
                    C.TIME_UNSET -> null
                    else -> duration
                }
                _positionMs.value = player.contentPosition
                delay(1.seconds)
            }
        }
        if (player.isPlaying) {
            updateCurrentSong(player.mediaMetadata)
            updateUpNext()
            _playState.value = PlayState.Playing
        }
    }

    fun addPlaylistItem(song: Playlist.Song) {
        val uri = song.id.toAudioUri()
        val mediaItem = MediaItem.Builder().run {
            setMediaMetadata(MediaMetadata.Builder().run {
                setTitle(song.title)
                setUri(uri)
                setExtras(Bundle().apply {
                    putStringArrayList(MUSIC_METADATA_CATEGORIES, ArrayList(song.categories))
                    putString(MUSIC_METADATA_THUMBNAIL_ID, song.id.toThumbnailUri().toString())
                    putLong(MUSIC_METADATA_DURATION, song.duration)
                })
                build()
            })
            setUri(uri)
            build()
        }
        addMediaItem(mediaItem)
    }

    private fun addMediaItem(mediaItem: MediaItem) {
        player.addMediaItem(mediaItem)
        Log.d("PlayerViewModel", "added video uri: $mediaItem")
        updateUpNext()

        if (!player.isPlaying) {
            player.play()
        }
    }

    private fun updateCurrentSong(meta: MediaMetadata) = meta.run {
        _currentSong.value = CurrentSong(
            title = title?.toString() ?: "unknown title",
            categories = extras?.getStringArrayList(MUSIC_METADATA_CATEGORIES)
                ?: ArrayList(),
            thumbNailUri = extras?.getString(MUSIC_METADATA_THUMBNAIL_ID)?.let(Uri::parse)
        )
    }

    private fun updateUpNext() {
        _nextUp.value =
            (player.currentMediaItemIndex..<player.mediaItemCount)
                .asSequence()
                .drop(1)
                .take(5)
                .map { player.getMediaItemAt(it) }
                .map { it.mediaMetadata.title?.toString() ?: "No title" }
                .toList()
                .also {
                    Log.d("PlayerViewModel", "updating up next to: $it")
                }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }

    private class Listener(val viewModel: PlayerViewModel) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            viewModel._playState.value = if (isPlaying) PlayState.Playing else PlayState.Paused
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaMetadata?.run {
                viewModel.updateCurrentSong(this)
            }
            viewModel.updateUpNext()
        }
    }
}

data class CurrentSong(
    val title: String,
    val categories: ArrayList<String>,
    val thumbNailUri: Uri?,
)

enum class PlayState {
    Playing,
    Paused,
}