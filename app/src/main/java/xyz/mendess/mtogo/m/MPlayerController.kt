package xyz.mendess.mtogo.m

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.m.CustomCommands.sendCustomCommand
import xyz.mendess.mtogo.util.MediaItems
import xyz.mendess.mtogo.util.MediaItems.Companion.MUSIC_METADATA_CATEGORIES
import xyz.mendess.mtogo.util.MediaItems.Companion.MUSIC_METADATA_THUMBNAIL_ID
import xyz.mendess.mtogo.util.await
import xyz.mendess.mtogo.viewmodels.Playlist
import kotlin.time.Duration.Companion.seconds

class MPlayerController(
    val scope: CoroutineScope,
    private val player: MediaController,
) : Player by player {

    val mediaItems = MediaItems()

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

    private val _lastQueue: MutableStateFlow<UInt?> = MutableStateFlow(null)
    val lastQueue = _lastQueue.asStateFlow()

    init {
        player.addListener(Listener(this))
        scope.launch {
            while (true) {
                _totalDurationMs.value = when (val duration = player.duration) {
                    C.TIME_UNSET -> null
                    else -> duration
                }
                _positionMs.value = player.contentPosition
                delay(1.seconds)
            }
        }
        updateCurrentSong()
        updateUpNext()
        if (player.isPlaying) {
            _playState.value = PlayState.Playing
        }
    }

    fun queuePlaylistItem(song: Playlist.Song, move: Boolean = true) {
        scope.launch {
            queueMediaItem(mediaItems.fromPlaylistSong(song), move, notBatching = true)
        }
    }

    fun queuePlaylistItems(songs: Sequence<Playlist.Song>) {
        scope.launch {
            queueMediaItems(songs.map(mediaItems::fromPlaylistSong))
        }
    }

    private suspend fun queueMediaItems(items: Sequence<ParcelableMediaItem>) {
        player.sendCustomCommand(CustomCommands.QUEUE_PLAYLIST_ITEMS, Bundle().apply {
            putParcelableArrayList("items", items.toCollection(ArrayList()))
        }).await()
    }

    suspend fun queueMediaItem(
        item: ParcelableMediaItem,
        move: Boolean = true,
        notBatching: Boolean = true
    ) {
        player.sendCustomCommand(CustomCommands.QUEUE_PLAYLIST_ITEM, Bundle().apply {
            putParcelable("item", item)
            putBoolean("move", move)
            putBoolean("notBatching", notBatching)
        }).await()
    }

    private suspend fun getLastQueue(): UInt? {
        return player
            .sendCustomCommand(CustomCommands.LAST_QUEUE, Bundle.EMPTY)
            .await()
            .extras
            .getInt("lastQueue", -1)
            .let { if (it < 0) null else it.toUInt() }
    }

    private fun updateCurrentSong(meta: MediaMetadata? = null) = meta.run {
        _currentSong.value = meta?.toCurrentSong() ?: currentSong()
    }

    private fun updateUpNext() {
        _nextUp.value = player.nextSongs(5U)
    }

    suspend fun resetLastQueue() {
        player
            .sendCustomCommand(CustomCommands.RESET_LAST_QUEUE, Bundle.EMPTY)
            .await()
        _lastQueue.value = null
    }

    private class Listener(val viewModel: MPlayerController) : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            viewModel._nextUp.value = viewModel.player.nextSongs(5U)
            viewModel.scope.launch {
                viewModel._lastQueue.value = viewModel.getLastQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
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
                Player.STATE_ENDED -> {
                }
                Player.STATE_IDLE -> {
                    if (viewModel.player.playerError != null) {
                        viewModel.player.removeMediaItem(viewModel.player.currentMediaItemIndex)
                        viewModel.player.prepare()
                        viewModel.player.play()
                    }
                }
                Player.STATE_READY -> {
                    viewModel._playState.value = PlayState.Ready
                }
                Player.STATE_BUFFERING -> {
                    viewModel._playState.value = PlayState.Buffering
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaMetadata?.run {
                viewModel.updateCurrentSong(this)
            }
            viewModel.updateUpNext()
        }
    }
}

data class ParcelableMediaItem(
    val uri: Uri,
    val title: String? = null,
    val thumbnailUri: Uri? = null,
    val categories: List<String> = emptyList(),
) : Parcelable {
    constructor(parcel: Parcel) : this(
        uri = Uri.parse(parcel.readString()),
        title = parcel.readString()!!,
        thumbnailUri = Uri.parse(parcel.readString()),
        categories = mutableListOf<String>().apply {
            parcel.readStringList(this)
        }
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, p1: Int) {
        parcel.writeString(uri.toString())
        parcel.writeString(title)
        parcel.writeString(thumbnailUri.toString())
        parcel.writeStringList(categories)
    }

    companion object CREATOR : Parcelable.Creator<ParcelableMediaItem> {
        override fun createFromParcel(parcel: Parcel): ParcelableMediaItem {
            return ParcelableMediaItem(parcel)
        }

        override fun newArray(size: Int): Array<ParcelableMediaItem?> {
            return arrayOfNulls(size)
        }
    }

    fun toMediaItem(): MediaItem = MediaItem.Builder().run {
        setMediaMetadata(MediaMetadata.Builder().run {
            if (title != null) setTitle(title)
            setUri(uri)
            setExtras(Bundle().apply {
                if (categories.isNotEmpty()) putStringArrayList(
                    MUSIC_METADATA_CATEGORIES,
                    ArrayList(categories)
                )
                if (thumbnailUri != null) putString(
                    MUSIC_METADATA_THUMBNAIL_ID,
                    thumbnailUri.toString(),
                )
            })
            build()
        })
        setUri(uri)
        build()
    }

}