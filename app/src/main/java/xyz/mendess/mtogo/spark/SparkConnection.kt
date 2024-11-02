@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.spark

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.data.StoredCredentialsState
import xyz.mendess.mtogo.m.currentSong
import xyz.mendess.mtogo.m.daemon.MPlayer
import xyz.mendess.mtogo.m.daemon.MediaItems
import xyz.mendess.mtogo.m.daemon.ParcelableMediaItem
import xyz.mendess.mtogo.m.nextSongs
import xyz.mendess.mtogo.m.prevSongs
import xyz.mendess.mtogo.util.orelse
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel

class SparkConnection(
    context: Context,
    settings: Settings,
    hostname: String,
    private val mediaItems: MediaItems,
    private val player: MPlayer,
    private val scope: CoroutineScope,
) {
    init {
        scope.launch { connectToBackend(settings, hostname) }
    }

    private val playlist by lazy { PlaylistViewModel(context) }

    private suspend fun connectToBackend(settings: Settings, hostname: String) {
        var lastSocket: SocketIo? = null
        try {
            settings.credentials
                .mapNotNull {
                    when (it) {
                        StoredCredentialsState.Loading -> null
                        is StoredCredentialsState.Loaded -> it.credentials
                    }
                }
                .map { credentials ->
                    lastSocket?.disconnect()
                    SocketIo(credentials, hostname)
                        .also { socket -> lastSocket = socket }
                }
                .collect { socket ->
                    socket.onWithAck(scope, "command", this::handleCommand)
                }
        } finally {
            lastSocket?.disconnect()
        }
    }

    private suspend fun handleCommand(
        command: Spark.Command,
    ): IntoSparkResponse = when (command) {
        Spark.Command.Heartbeat -> Spark.ErrorResponse.RequestFailed("unsupported")
        is Spark.Command.Music -> handleMusicCommand(command.music)
        Spark.Command.Reload -> Spark.ErrorResponse.RequestFailed("unsupported")
        Spark.Command.Version -> Spark.ErrorResponse.RequestFailed("TODO")
    }

    private suspend fun handleMusicCommand(cmd: Spark.MusicCmd): IntoSparkResponse {
        return when (val cmd = cmd.command) {
            Spark.MusicCmdKind.Frwd -> {
                player.seekToNextMediaItem()
                Spark.MusicResponse.Title(player.currentSong()?.title ?: "no title")
            }

            Spark.MusicCmdKind.Back -> {
                player.seekToPreviousMediaItem()
                Spark.MusicResponse.Title(player.currentSong()?.title ?: "no title")
            }

            Spark.MusicCmdKind.CyclePause -> {
                player.cyclePause()
                Spark.MusicResponse.PlayState(paused = !player.isPlaying)
            }

            is Spark.MusicCmdKind.ChangeVolume -> {
                Spark.MusicResponse.Volume(player.changeVolume(cmd.amount))
            }

            Spark.MusicCmdKind.Current -> {
                player.current() ?: Spark.ErrorResponse.RequestFailed("nothing playing")
            }

            is Spark.MusicCmdKind.Queue -> {
                val mediaItem = if (cmd.search) {
                    mediaItems.parcelableFromSearch(cmd.query).orelse {
                        return Spark.ErrorResponse.RequestFailed("failed search for ${cmd.query}: $it")
                    }
                } else {
                    val song = playlist.get().findByName(cmd.query)
                        ?: return Spark.ErrorResponse.RequestFailed("Song not in playlist")
                    ParcelableMediaItem.PlaylistItem(song.id)
                }
                player.queueMediaItem(mediaItem)
            }

            is Spark.MusicCmdKind.Now -> {
                val currentSong = player.currentSong()
                    ?: return Spark.ErrorResponse.RequestFailed("nothing playing")
                val amount = cmd.amount ?: 10u
                val prevCount = amount / 5u
                val nextCount = (amount.toInt() - prevCount.toInt() - 1).coerceAtLeast(0)
                Spark.MusicResponse.Now(
                    before = player.prevSongs(prevCount),
                    current = currentSong.title,
                    after = player.nextSongs(nextCount.toUInt())
                )
            }
        }
    }
}