@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.m

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.data.StoredCredentialsState
import xyz.mendess.mtogo.spark.IntoSparkResponse
import xyz.mendess.mtogo.spark.SocketIo
import xyz.mendess.mtogo.spark.Spark
import xyz.mendess.mtogo.util.dataStore
import xyz.mendess.mtogo.util.hostname
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Duration.Companion.milliseconds

typealias Action = (MPlayer, Bundle) -> SessionResult

@OptIn(UnstableApi::class)
object CustomCommands {

    fun MediaController.sendCustomCommand(
        cmd: Pair<SessionCommand, Action>,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return sendCustomCommand(cmd.first, args)
    }

    private fun command(action: Action) =
        ReadOnlyProperty<Any?, Pair<SessionCommand, Action>> { _, property ->
            SessionCommand(property.name, Bundle.EMPTY) to action
        }

    private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
        getParcelable(key, T::class.java)

    private inline fun <reified T : Parcelable> Bundle.parcelableList(key: String): ArrayList<T>? =
        getParcelableArrayList(key, T::class.java)

    // deserialization error
    private val deError = SessionResult(SessionError.ERROR_BAD_VALUE)

    val QUEUE_PLAYLIST_ITEM by command h@{ session: MPlayer, args: Bundle ->
        session.queueMediaItem(
            mediaItem = args.parcelable("item") ?: return@h deError,
            move = args.getBoolean("move"),
            notBatching = args.getBoolean("notBatching")
        )
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
    val QUEUE_PLAYLIST_ITEMS by command h@{ session, args ->
        val mediaItems = args.parcelableList<ParcelableMediaItem>("items") ?: return@h deError
        session.queueMediaItems(mediaItems.asSequence())
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
    val LAST_QUEUE by command { session, _ ->
        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
            session.lastQueue.value?.let { putInt("lastQueue", it.toInt()) }
        })
    }
    val RESET_LAST_QUEUE by command { session, _ ->
        session.lastQueue.value = null
        SessionResult(SessionResult.RESULT_SUCCESS)
    }

    val all get() = handlers.map { it.key }
    val handlers: Map<SessionCommand, Action> = mapOf(
        QUEUE_PLAYLIST_ITEM, QUEUE_PLAYLIST_ITEMS, LAST_QUEUE, RESET_LAST_QUEUE,
    )
}

// TODO: https://developer.android.com/media/media3/session/background-playback#resumption
class MService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val playlist = PlaylistViewModel()

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(dataStore)
        val player = ExoPlayer.Builder(this).build().let { MPlayer(scope, it) }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                @OptIn(UnstableApi::class)
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ConnectionResult = AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .addSessionCommands(CustomCommands.all)
                            .build()
                    )
                    .build()

                @OptIn(UnstableApi::class)
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> = Futures.immediateFuture(
                    CustomCommands.handlers[customCommand]!!(player, args)
                )
            })
            .build()
        scope.launch { connectToBackend(settings, hostname, player) }
    }

    // The user dismissed the app from the recent tasks
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        mediaSession?.player?.apply {
            if (playWhenReady) {
                pause()
            }
        }
        this.stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }


    override fun onDestroy() {
        job.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private suspend fun connectToBackend(settings: Settings, hostname: String, player: MPlayer) {
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
                    socket.onWithAck(scope, "command") {
                        handleCommand(it, player, playlist)
                    }
                }
        } finally {
            lastSocket?.disconnect()
        }
    }
}

private suspend fun handleCommand(
    command: Spark.Command,
    player: MPlayer,
    playlist: PlaylistViewModel,
): IntoSparkResponse {
    if (command !is Spark.Command.Music)
        return Spark.ErrorResponse.RequestFailed("unsupported command $command")
    return when (val cmd = command.music.command) {
        Spark.MusicCmdKind.Frwd -> {
            player.seekToNextMediaItem()
            Spark.MusicResponse.Title(player.currentSong()?.title ?: "no title")
        }

        Spark.MusicCmdKind.Back -> {
            player.seekToPreviousMediaItem()
            Spark.MusicResponse.Title(player.currentSong()?.title ?: "no title")
        }

        Spark.MusicCmdKind.CyclePause -> {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            Spark.MusicResponse.PlayState(paused = !player.isPlaying)
        }

        is Spark.MusicCmdKind.ChangeVolume -> {
            player.volume += cmd.amount.toFloat() / 100
            Spark.MusicResponse.Volume(player.volume.toDouble() * 100)
        }

        Spark.MusicCmdKind.Current -> {
            val currentSong = player.currentSong()
                ?: return Spark.ErrorResponse.RequestFailed("nothing playing")
            val totalDurationMs = player.totalDurationMs
                ?: return Spark.ErrorResponse.RequestFailed("nothing playing")
            val positionMs = player.positionMs
            Spark.MusicResponse.Current(
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

        is Spark.MusicCmdKind.Queue -> {
            val mediaItem = if (cmd.search && !cmd.query.startsWith("http")) {
                player.mediaItems.fromSearch(cmd.query).fold(
                    onSuccess = { it },
                    onFailure = {
                        return Spark.ErrorResponse.RequestFailed("search failed $it")
                    }
                )
            } else {
                val song = playlist.get().findByName(cmd.query)
                    ?: return Spark.ErrorResponse.RequestFailed("Song not in playlist")
                player.mediaItems.fromPlaylistSong(song)
            }
            player.queueMediaItem(mediaItem)
        }

        is Spark.MusicCmdKind.Now -> {
            val currentSong = player.currentSong()
            Spark.MusicResponse.Now(
                before = emptyList(),
                current = currentSong?.title ?: "nothing playing",
                after = player.nextSongs(cmd.amount ?: 10U)
            )
        }
    }.into()
}