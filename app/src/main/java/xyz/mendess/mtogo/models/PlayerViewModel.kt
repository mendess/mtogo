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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.util.Mutex
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

    private var lastQueue: Mutex<Int?> = Mutex(null)

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

    fun queuePlaylistItems(songs: Sequence<Playlist.Song>) {
        viewModelScope.launch {
            queueMediaItems(
                songs.map { song ->
                    val uri = song.id.toAudioUri()
                    MediaItem.Builder().run {
                        setMediaMetadata(MediaMetadata.Builder().run {
                            setTitle(song.title)
                            setUri(uri)
                            setExtras(Bundle().apply {
                                putStringArrayList(
                                    MUSIC_METADATA_CATEGORIES,
                                    ArrayList(song.categories)
                                )
                                putString(
                                    MUSIC_METADATA_THUMBNAIL_ID,
                                    song.id.toThumbnailUri().toString()
                                )
                                putLong(MUSIC_METADATA_DURATION, song.duration)
                            })
                            build()
                        })
                        setUri(uri)
                        build()
                    }
                }
            )
        }
    }

    private suspend fun queueMediaItems(mediaItems: Sequence<MediaItem>) {
        val move = player.mediaItemCount != 0
        mediaItems.forEach { mediaItem ->
            player.addMediaItem(mediaItem)
            val queuedPosition = player.mediaItemCount - 1
            Log.d("PlayerViewModel", "added video uri: $mediaItem")
            if (move) {
                lastQueue.write { ref ->
                    val moveTo = (ref.t ?: player.currentMediaItemIndex) + 1
                    player.moveMediaItem(queuedPosition, moveTo)
                    Log.d(
                        "PlayerViewModel",
                        "Moved from $queuedPosition to $moveTo [current: ${player.currentMediaItemIndex}]"
                    )
                    ref.t = moveTo
                }
            }
        }
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
            Log.d("PlayerViewModel", "onIsPlayingChanged: $isPlaying")
            viewModel._playState.update { s ->
                if (s != PlayState.Buffering) {
                    if (isPlaying) PlayState.Playing else PlayState.Paused
                } else {
                    PlayState.Buffering
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_ENDED -> Log.d("PlayerViewModel", "STATE: ended")
                Player.STATE_IDLE -> Log.d("PlayerViewModel", "STATE: idle")
                Player.STATE_READY -> {
                    Log.d("PlayerViewModel", "STATE: ready")
                    viewModel._playState.value = PlayState.Ready
                }
                Player.STATE_BUFFERING -> {
                    Log.d("PlayerViewModel", "STATE: buffering")
                    viewModel._playState.value = PlayState.Buffering
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaMetadata?.run {
                viewModel.updateCurrentSong(this)
            }
            viewModel.updateUpNext()
            viewModel.viewModelScope.launch {
                viewModel.lastQueue.write { ref ->
                    if (ref.t?.let { it < viewModel.player.currentMediaItemIndex } == true) {
                        ref.t = null
                    }
                }
            }
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
    Buffering,
    Ready,
}