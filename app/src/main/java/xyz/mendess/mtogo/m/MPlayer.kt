@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.m

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.spark.Spark
import xyz.mendess.mtogo.util.MediaItems
import java.io.Closeable
import kotlin.math.max

class MPlayer(
    val scope: CoroutineScope,
    private val player: Player,
) : Player by player, Closeable {
    val mediaItems = MediaItems()

    val lastQueue: MutableStateFlow<UInt?> = MutableStateFlow(null)

    init {
        player.prepare()
        player.addListener(Listener(this))
    }

    fun queueMediaItems(mediaItems: Sequence<ParcelableMediaItem>) {
        val move = player.mediaItemCount != 0
        mediaItems.forEach { mediaItem ->
            queueMediaItem(mediaItem, move, notBatching = false)
        }
        if (!player.isPlaying) {
            player.play()
        }
    }

    fun queueMediaItem(
        mediaItem: ParcelableMediaItem,
        move: Boolean = true,
        notBatching: Boolean = true,
    ): Spark.MusicResponse.QueueSummary {
        player.addMediaItem(mediaItem.toMediaItem())
        Log.d("MPlayer", "added video uri: $mediaItem")
        if (notBatching && !player.isPlaying) {
            player.play()
        }
        val queuedPosition = max(player.mediaItemCount - 1, 0).toUInt()
        val currentPosition = player.currentMediaItemIndex.toUInt()
        Log.d(
            "MPlayer",
            "move: $move | queuedPosition: $queuedPosition | currentPosition: $currentPosition | mediaItemCount: ${player.mediaItemCount} | currentMediaItemIndex: ${player.currentMediaItemIndex}"
        )
        return if (move && queuedPosition != currentPosition) {
            val movedTo = lastQueue.updateAndGet { v ->
                (v ?: currentPosition) + 1U
            }!!
            if (queuedPosition != movedTo) {
                player.moveMediaItem(queuedPosition.toInt(), movedTo.toInt())
            }
            Spark.MusicResponse.QueueSummary(
                from = queuedPosition,
                movedTo = movedTo,
                current = currentPosition,
            )
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

    override fun close() {
        mediaItems.close()
        player.release()
    }

    private class Listener(val viewModel: MPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            viewModel.scope.launch {
                viewModel.lastQueue.updateAndGet {
                    if (it == null || it > viewModel.player.currentMediaItemIndex.toUInt()) null
                    else it
                }.also {
                    Log.d("MPlayer", "updated last queue to $it")
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

