package xyz.mendess.mtogo.m

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.OptIn
import androidx.media3.common.Player
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.spark.SparkConnection
import xyz.mendess.mtogo.util.hostname
import kotlin.properties.ReadOnlyProperty

typealias Action = suspend (MPlayer, Bundle) -> SessionResult

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

    // deserialization error
    private val deError = SessionResult(SessionError.ERROR_BAD_VALUE)

    val QUEUE_PLAYLIST_ITEM by command { session: MPlayer, args: Bundle ->
        session.queueMediaItem(
            mediaItem = args.parcelable("item") ?: return@command deError,
        )
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
    val QUEUE_PLAYLIST_ITEMS by command { session, args ->
        val mediaItems = args.parcelableList<ParcelableMediaItem>("items") ?: return@command deError
        session.queueMediaItems(mediaItems.asSequence())
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
    val LAST_QUEUE by command { session, _ ->
        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
            session.lastQueue?.let { putInt("lastQueue", it.toInt()) }
        })
    }
    val RESET_LAST_QUEUE by command { session, _ ->
        session.lastQueue = null
        SessionResult(SessionResult.RESULT_SUCCESS)
    }

    val all get() = handlers.map { it.key }
    val handlers: Map<SessionCommand, Action> = mapOf(
        QUEUE_PLAYLIST_ITEM, QUEUE_PLAYLIST_ITEMS, LAST_QUEUE, RESET_LAST_QUEUE,
    )
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
    getParcelable(key) as T?

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.parcelableList(key: String): ArrayList<T>? =
    getParcelableArrayList(key)

// TODO: https://developer.android.com/media/media3/session/background-playback#resumption
class MService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var spark: SparkConnection? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this, scope)
        val player = ExoPlayer.Builder(this)
            .setDeviceVolumeControlEnabled(true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
            }
            .let { MPlayer(scope, it) }
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
                ): ListenableFuture<SessionResult> = scope.future {
                    CustomCommands.handlers[customCommand]!!(player, args)
                }

            })
            .build()
        spark = SparkConnection(settings, hostname, player, scope)
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
}