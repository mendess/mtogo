package xyz.mendess.mtogo.services

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.data.StoredCredentialsState
import xyz.mendess.mtogo.ui.dataStore
import xyz.mendess.mtogo.util.IntoSparkResponse
import xyz.mendess.mtogo.util.MPlayer
import xyz.mendess.mtogo.util.SocketIo
import xyz.mendess.mtogo.util.Spark
import kotlin.time.Duration.Companion.milliseconds
import android.provider.Settings as AndroidSettings


// TODO: https://developer.android.com/media/media3/session/background-playback#resumption
class MService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(dataStore)
        val hostname = AndroidSettings.Global.getString(contentResolver, "device_name")
        val player = ExoPlayer.Builder(this).build().let { MPlayer(scope, it) }
        mediaSession = MediaSession.Builder(this, player).build()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

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
                        handleCommand(it, player)
                    }
                }
        } finally {
            lastSocket?.disconnect()
        }
    }
}

private suspend fun handleCommand(command: Spark.Command, player: MPlayer): IntoSparkResponse =
    with(player) {
        if (command !is Spark.Command.Music)
            return Spark.ErrorResponse.RequestFailed("unsupported command $command")
        when (val cmd = command.music.command) {
            Spark.MusicCmdKind.Frwd -> {
                player.seekToNextMediaItem()
                Spark.MusicResponse.Title(currentSong.value?.title ?: "unknown title")
            }

            Spark.MusicCmdKind.Back -> {
                player.seekToPreviousMediaItem()
                Spark.MusicResponse.Title(currentSong.value?.title ?: "unknown title")
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
                val currentSong = currentSong.value
                    ?: return Spark.ErrorResponse.RequestFailed("nothing playing")
                val totalDurationMs = totalDurationMs.value
                    ?: return Spark.ErrorResponse.RequestFailed("nothing playing")
                Spark.MusicResponse.Current(
                    title = currentSong.title,
                    chapter = null,
                    playing = player.isPlaying,
                    volume = player.volume.toDouble() * 100,
                    progress = (positionMs.value.toDouble() / totalDurationMs) * 100,
                    playbackTime = positionMs.value.milliseconds,
                    duration = totalDurationMs.milliseconds,
                    categories = currentSong.categories,
                    index = 0U,
                    next = nextUp.value.firstOrNull()
                )
            }

            is Spark.MusicCmdKind.Queue -> {
                val mediaItem = if (cmd.search && cmd.query.startsWith("http")) {
                    mediaItemFromSearch(cmd.query).fold(
                        onSuccess = { it },
                        onFailure = {
                            return Spark.ErrorResponse.RequestFailed("search failed $it")
                        }
                    )
                } else {
                    mediaItemFromUrl(cmd.query)
                }
                queueMediaItem(mediaItem)
            }

            is Spark.MusicCmdKind.Now -> {
                Spark.MusicResponse.Now(
                    before = emptyList(),
                    current = currentSong.value?.title ?: "nothing playing",
                    after = nextSongs(cmd.amount ?: 10U).toList()
                )
            }
        }.into()
    }