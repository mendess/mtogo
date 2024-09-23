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
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.spark.SparkConnection
import xyz.mendess.mtogo.util.dataStore
import xyz.mendess.mtogo.util.hostname
import kotlin.properties.ReadOnlyProperty

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

private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
    getParcelable(key, T::class.java)

private inline fun <reified T : Parcelable> Bundle.parcelableList(key: String): ArrayList<T>? =
    getParcelableArrayList(key, T::class.java)

// TODO: https://developer.android.com/media/media3/session/background-playback#resumption
class MService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var spark: SparkConnection? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

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