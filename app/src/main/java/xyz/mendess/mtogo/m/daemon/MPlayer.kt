@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.m.daemon

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.m.currentSong
import xyz.mendess.mtogo.m.nextSongs
import xyz.mendess.mtogo.m.positionMs
import xyz.mendess.mtogo.m.totalDurationMs
import xyz.mendess.mtogo.spark.Spark
import xyz.mendess.mtogo.util.mapConcurrent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private const val LAST_QUEUE_NULL = -1

typealias OneShot<T> = Channel<T>

fun <T> OneShot(): OneShot<T> = Channel(CONFLATED)

class MPlayer(
    val scope: CoroutineScope,
    private val player: Player,
    private val mediaItems: MediaItems,
    private val context: Context,
) : Player by player, Closeable {
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

    fun changeVolume(delta: Int): Double {
        /// 30 - 100
        /// x  - delta
        /// x = delta * 30 / 100
        val mappedDelta = ((delta * 30).toDouble() / 100).roundToInt()
        player.setDeviceVolume(
            (player.deviceVolume + mappedDelta)
                .coerceIn(0, player.deviceInfo.maxVolume),
            0 // flags
        )
        // 30 - 100
        /// volume - mapped
        /// mapped = volume * 100 / 30
        return ((player.deviceVolume * 100).toDouble() / 30)
    }

    suspend fun queueMediaItems(items: Sequence<ParcelableMediaItem>) {
        val oneshot = OneShot<Spark.MusicResponse.QueueSummary>()
        val isAlmostEmpty = mediaItemCount < 2
        val itemFlow = items
            .mapIndexed { i, item -> i to item }
            .asFlow()
            .mapConcurrent(scope, size = 2U) { (i, item) ->
                mediaItems.fromParcelable(context, item, isAlmostEmpty && i < 2)
            }
        mediaItemQueue.send(itemFlow to oneshot)
        oneshot.receive()
        lastQueue = null
    }

    suspend fun queueMediaItem(mediaItem: ParcelableMediaItem): Spark.MusicResponse.QueueSummary {
        val item = mediaItems.fromParcelable(
            context,
            mediaItem,
            caching = player.mediaItemCount < 2
        )
        val oneshot = OneShot<Spark.MusicResponse.QueueSummary>()
        mediaItemQueue.send(flowOf(item) to oneshot)
        return oneshot.receive()
    }

    private val mediaItemQueue =
        Channel<Pair<Flow<MediaItem>, OneShot<Spark.MusicResponse.QueueSummary>>>(
            capacity = 200,
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
            mediaItemQueue.consumeEach { (items, oneshot) ->
                if (player.mediaItemCount == 0) { // if there is nothing playing just set the items
                    player.setMediaItems(items.toCollection(ArrayList()))
                    oneshot.send(
                        Spark.MusicResponse.QueueSummary(from = 0U, movedTo = 0U, current = 0U)
                    )
                } else {
                    val channel = OneShot<Spark.MusicResponse.QueueSummary>()
                    items.collect { queueMediaItemImpl(it, channel) }
                    oneshot.send(channel.receive())
                }
            }
        }
    }

    private fun queueMediaItemImpl(
        mediaItem: MediaItem,
        oneshot: OneShot<Spark.MusicResponse.QueueSummary>
    ) {
        val moveTo = _lastQueue.updateAndGet {
            (if (it == LAST_QUEUE_NULL) player.currentMediaItemIndex else it) + 1
        }.toUInt()
        player.addMediaItem(mediaItem)
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
        oneshot.trySend(summary)
    }

    override fun close() {
        player.release()
    }

    private class Listener(val viewModel: MPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            viewModel.scope.launch {
                viewModel._lastQueue.updateAndGet {
                    if (it <= viewModel.player.currentMediaItemIndex) -1
                    else it
                }
                with(viewModel.player) {
                    if (mediaItemCount == 0) return@with
                    val nextItem = (currentMediaItemIndex + 1) % mediaItemCount
                    val mediaItem = viewModel
                        .mediaItems
                        .caching(viewModel.context, getMediaItemAt(nextItem))
                    if (mediaItem != null) replaceMediaItem(nextItem, mediaItem)
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

