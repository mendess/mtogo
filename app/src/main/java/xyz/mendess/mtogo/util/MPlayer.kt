@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.util

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.viewmodels.Playlist
import xyz.mendess.mtogo.viewmodels.VideoId
import java.io.Closeable
import java.net.URLEncoder
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

private const val MUSIC_METADATA_CATEGORIES = "cat"
private const val MUSIC_METADATA_THUMBNAIL_ID = "thumb"
private const val MUSIC_METADATA_DURATION = "dur"

class MPlayer(
    val scope: CoroutineScope,
    private val player: Player,
) : Player by player, Closeable {
    private val http = HttpClient(CIO)
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

    private var lastQueue: Mutex<UInt?> = Mutex(null)

    init {
        player.prepare()
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
        if (player.isPlaying) {
            updateCurrentSong(player.mediaMetadata)
            updateUpNext()
            _playState.value = PlayState.Playing
        }
    }

    fun queuePlaylistItem(song: Playlist.Song, move: Boolean = true) {
        scope.launch {
            queueMediaItem(mediaItemFromPlaylistItem(song), move, autoplay = true)
            updateUpNext()
        }
    }

    fun queuePlaylistItems(songs: Sequence<Playlist.Song>) {
        scope.launch { queueMediaItems(songs.map(::mediaItemFromPlaylistItem)) }
    }

    suspend fun mediaItemFromSearch(query: String): Result<MediaItem> {
        Log.d("MPlayer", "queueing search $query")
        val query = withContext(Dispatchers.IO) { URLEncoder.encode(query, "utf-8") }
        return runCatching {
            val resp = http.get("https://mendess.xyz/api/v1/playlist/search/${query}")
            val id = resp.bodyAsText()
            Log.d("MPlayer", "id: $id")
            mediaItemFromVideoId(VideoId(id))
        }
    }

    private val metadataJson = Json { ignoreUnknownKeys = true }

    private suspend fun mediaItemFromVideoId(id: VideoId) = MediaItem.Builder().run {
        val audioUri = id.toAudioUri()
        val titleUri = id.toMetadataUri()
        val title = try {
            @Serializable
            data class Metadata(val title: String)

            val response = http.get(titleUri.toString())
            metadataJson.decodeFromString<Metadata>(response.bodyAsText()).title
        } catch (e: Exception) {
            "error getting title: $e"
        }

        setMediaMetadata(MediaMetadata.Builder().run {
            setUri(audioUri)
            setTitle(title)
            setExtras(Bundle().apply {
                putString(MUSIC_METADATA_THUMBNAIL_ID, id.toThumbnailUri().toString())
            })
            build()
        })
        setUri(audioUri)
        build()
    }

    suspend fun mediaItemFromUrl(url: String): MediaItem {
        Log.d("MPlayer", "queueing $url")
        val uri = Uri.parse(url)
        val vidId = if (uri.host?.contains(SHORT_YT_HOST) == true) {
            uri.path?.let(::VideoId)
        } else if (uri.host?.contains(LONG_YT_REGEX) == true) {
            uri.getQueryParameter("v")?.let(::VideoId)
        } else {
            null
        }
        return vidId?.let { mediaItemFromVideoId(it) } ?: MediaItem.fromUri(uri)
    }

    fun nextSongs(count: UInt): Sequence<String> {
        return (player.currentMediaItemIndex..<player.mediaItemCount)
            .asSequence()
            .drop(1)
            .take(count.toInt())
            .map { player.getMediaItemAt(it) }
            .map { it.mediaMetadata.title?.toString() ?: "No title" }
    }

    private suspend fun queueMediaItems(mediaItems: Sequence<MediaItem>) {
        val move = player.mediaItemCount != 0
        mediaItems.forEach { mediaItem ->
            queueMediaItem(mediaItem, move, autoplay = false)
        }
        updateUpNext()
        if (!player.isPlaying) {
            player.play()
        }
    }

    suspend fun queueMediaItem(
        mediaItem: MediaItem,
        move: Boolean = true,
        autoplay: Boolean = true,
    ): Spark.MusicResponse.QueueSummary {
        player.addMediaItem(mediaItem)
        if (autoplay && !player.isPlaying) {
            player.play()
        }
        val queuedPosition = min(player.mediaItemCount - 1, 0).toUInt()
        val currentPosition = player.currentMediaItemIndex.toUInt()
        Log.d("MPlayer", "added video uri: $mediaItem")
        return if (move && queuedPosition != currentPosition) {
            lastQueue.write { ref ->
                Log.d("MPlayer", "last queue ${ref.t}")
                val moveTo = (ref.t ?: currentPosition) + 1U
                player.moveMediaItem(queuedPosition.toInt(), moveTo.toInt())
                ref.t = moveTo
                Spark.MusicResponse.QueueSummary(
                    from = queuedPosition,
                    movedTo = moveTo,
                    current = currentPosition,
                )
            }
        } else {
            Spark.MusicResponse.QueueSummary(
                from = queuedPosition,
                movedTo = queuedPosition,
                current = currentPosition,
            )
        }.also { summary ->
            Log.d(
                "MPlayer",
                "Moved from ${summary.from} to ${summary.movedTo} [current: ${summary.current}]"
            )
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
                    Log.d("MPlayer", "updating up next to: $it")
                }
    }

    override fun close() {
        http.closeQuietly()
        player.release()
    }

    private class Listener(val viewModel: MPlayer) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d("MPlayer", "onIsPlayingChanged: $isPlaying")
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
                Player.STATE_ENDED -> Log.d("MPlayer", "STATE: ended")
                Player.STATE_IDLE -> Log.d("MPlayer", "STATE: idle")
                Player.STATE_READY -> {
                    Log.d("MPlayer", "STATE: ready")
                    viewModel._playState.value = PlayState.Ready
                }

                Player.STATE_BUFFERING -> {
                    Log.d("MPlayer", "STATE: buffering")
                    viewModel._playState.value = PlayState.Buffering
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaMetadata?.run {
                viewModel.updateCurrentSong(this)
            }
            viewModel.updateUpNext()
            viewModel.scope.launch {
                viewModel.lastQueue.write { ref ->
                    if (ref.t?.let { it < viewModel.player.currentMediaItemIndex.toUInt() } == true) {
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

fun mediaItemFromPlaylistItem(song: Playlist.Song) = MediaItem.Builder().run {
    val uri = song.id.toAudioUri()
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

const val SHORT_YT_HOST = "youtu.be"
const val LONG_YT_REGEX = "youtube.com"


