package xyz.mendess.mtogo.m

import android.content.Context
import android.os.Bundle
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
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.m.daemon.CustomCommands
import xyz.mendess.mtogo.m.daemon.CustomCommands.sendCustomCommand
import xyz.mendess.mtogo.m.daemon.MediaItems
import xyz.mendess.mtogo.m.daemon.ParcelableMediaItem
import xyz.mendess.mtogo.m.daemon.PlayState
import xyz.mendess.mtogo.util.await
import xyz.mendess.mtogo.viewmodels.Playlist
import kotlin.time.Duration.Companion.seconds

class MPlayerController(
    private val player: MediaController,
    settings: Settings,
    context: Context,
    val scope: CoroutineScope,
) : Player by player {

    val mediaItems = MediaItems(settings, scope, context)

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

    fun queuePlaylistItems(songs: Sequence<Playlist.Song>, callback: suspend () -> Unit = {}) {
        scope.launch {
            queueMediaItems(songs.map { ParcelableMediaItem.PlaylistItem(it.id) })
            callback()
        }
    }

    private suspend fun queueMediaItems(items: Sequence<ParcelableMediaItem>) {
        player.sendCustomCommand(CustomCommands.QUEUE_PLAYLIST_ITEMS, Bundle().apply {
            putParcelableArrayList("items", items.toCollection(ArrayList()))
        }).await()
    }

    fun queuePlaylistItem(song: Playlist.Song, move: Boolean = true, callback: suspend () -> Unit = {}) {
        scope.launch {
            queueMediaItem(ParcelableMediaItem.PlaylistItem(song.id), move, notBatching = true, callback)
        }
    }

    suspend fun queueMediaItem(
        item: ParcelableMediaItem,
        move: Boolean = true,
        notBatching: Boolean = true,
        callback: suspend () -> Unit = {}
    ) {
        player.sendCustomCommand(CustomCommands.QUEUE_PLAYLIST_ITEM, Bundle().apply {
            putParcelable("item", item)
            putBoolean("move", move)
            putBoolean("notBatching", notBatching)
        }).await()
        callback()
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

