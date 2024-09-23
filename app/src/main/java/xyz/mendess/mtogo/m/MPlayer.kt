package xyz.mendess.mtogo.m

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.spark.Spark
import xyz.mendess.mtogo.util.MediaItems
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val LAST_QUEUE_NULL = -1

typealias OneShot<T> = Channel<T>

fun <T> OneShot(): OneShot<T> = Channel(CONFLATED)

class MPlayer(
    val scope: CoroutineScope,
    private val player: Player,
) : Player by player, Closeable {
    val mediaItems = MediaItems()
    fun cyclePause() = if (isPlaying) {
        pause()
    } else {
        play()
    }

    fun current(): Spark.MusicResponse.Current? {
        val currentSong = player.currentSong()
            ?: return null
        val totalDurationMs = player.totalDurationMs
            ?: return null
        val positionMs = player.positionMs
        return Spark.MusicResponse.Current(
            title = currentSong.title,
            chapter = null,
            playing = player.isPlaying,
            volume = player.volume.toDouble() * 100,
            progress = (positionMs.toDouble() / totalDurationMs) * 100,
            playbackTime = positionMs.milliseconds,
            duration = totalDurationMs.milliseconds,
            categories = currentSong.categories,
            index = 0U,
            next = player.nextSongs(1U).firstOrNull()
        )
    }

    suspend fun queueMediaItems(mediaItems: Sequence<ParcelableMediaItem>) {
        mediaItems.forEach { mediaItem ->
            val oneshot = OneShot<Spark.MusicResponse.QueueSummary>()
            mediaItemQueue.send(mediaItem to oneshot)
            oneshot.receive()
        }
        lastQueue = null
    }

    suspend fun queueMediaItem(mediaItem: ParcelableMediaItem): Spark.MusicResponse.QueueSummary {
        val oneshot = OneShot<Spark.MusicResponse.QueueSummary>()
        mediaItemQueue.send(mediaItem to oneshot)
        return oneshot.receive()
    }

    private val mediaItemQueue =
        Channel<Pair<ParcelableMediaItem, OneShot<Spark.MusicResponse.QueueSummary>?>>(
            capacity = 500,
            BufferOverflow.SUSPEND
        )

    private val _lastQueue = AtomicInteger(LAST_QUEUE_NULL)
    var lastQueue: UInt?
        get() = _lastQueue.get().let { if (it == LAST_QUEUE_NULL) null else it.toUInt() }
        set(value) {
            if (value == null) _lastQueue.set(LAST_QUEUE_NULL)
            else _lastQueue.set(value.toInt())
        }

    init {
        player.prepare()
        player.addListener(Listener(this))
        player.playWhenReady = true
        scope.launch {
            mediaItemQueue.consumeEach { (item, oneshot) ->
                queueMediaItemImpl(item, oneshot)
            }
        }
    }

    private fun queueMediaItemImpl(
        mediaItem: ParcelableMediaItem,
        oneshot: OneShot<Spark.MusicResponse.QueueSummary>?
    ) {
        val moveTo = _lastQueue.updateAndGet {
            (if (it == LAST_QUEUE_NULL) player.currentMediaItemIndex else it) + 1
        }.toUInt()
        player.addMediaItem(mediaItem.toMediaItem())
        val queuedPosition = max(player.mediaItemCount - 1, 0).toUInt()
        val currentPosition = player.currentMediaItemIndex.toUInt()
        val summary = if (queuedPosition != moveTo) {
            player.moveMediaItem(queuedPosition.toInt(), moveTo.toInt())
            Spark.MusicResponse.QueueSummary(
                from = queuedPosition,
                movedTo = moveTo,
                current = currentPosition,
            )
        } else {
            Spark.MusicResponse.QueueSummary(
                from = queuedPosition,
                movedTo = queuedPosition,
                current = currentPosition,
            )
        }
        oneshot?.trySend(summary)
    }

    override fun close() {
        mediaItems.close()
        player.release()
    }

    private class Listener(val viewModel: MPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            viewModel.scope.launch {
                viewModel._lastQueue.updateAndGet {
                    if (it <= viewModel.player.currentMediaItemIndex) -1
                    else it
                }
            }
        }
    }
}

enum class PlayState {
    Playing,
    Paused,
    Buffering,
    Ready,
}

