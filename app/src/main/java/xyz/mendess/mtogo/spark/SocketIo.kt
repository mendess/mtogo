package xyz.mendess.mtogo.spark

import android.net.Uri
import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections.singletonMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Credentials(val uri: Uri, val token: UUID)

class SocketIo(credentials: Credentials, hostname: String) {
    private val registeredEvents: ConcurrentHashMap<String, Unit> = ConcurrentHashMap()

    private val socket: Socket = run {
        Log.d("SocketIo", "connecting socket to $credentials")

        val options = IO.Options.builder()
            .setAuth(singletonMap("token", credentials.token.toString()))
            .build()
        IO.socket("${credentials.uri}/spark-protocol?h=$hostname", options).connect().apply {
            on(Socket.EVENT_CONNECT) { Log.d("SocketIo", "Socket connected!") }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.d("SocketIo", "connection failed: ${args.contentDeepToString()}")
            }
            io().on(Manager.EVENT_ERROR) { args ->
                Log.d(
                    "SocketIo",
                    "error: ${args.contentDeepToString()}"
                )
            }
        }
    }

    private fun register(event: String) {
        registeredEvents[event] = Unit
    }

    fun onWithAck(
        scope: CoroutineScope,
        event: String,
        handler: suspend (Spark.Command) -> IntoSparkResponse,
    ) {
        register(event)
        socket.on(event) { args ->
            scope.launch {
                val ack = args[1] as Ack
                val t = try {
                    Log.d("SocketIo", args[0].toString())
                    Spark.Command.fromJsonObject(args[0])
                } catch (e: SparkDeserializationError) {
                    Log.d("SocketIo", "error: ${e.stackTraceToString()}")
                    ack.call(
                        Spark.ErrorResponse.DeserializingCommand(e.toString()).into().toJSONObject()
                            .also { Log.d("SocketIo", "sending: $it") }
                    )
                    return@launch
                }
                val response = handler(t)
                ack.call(response.into().toJSONObject().also { Log.d("SocketIo", "sending: $it") })
            }
        }
    }

    fun disconnect() {
        Log.d("BackendViewModel", "disconnecting socket")
        socket.disconnect()
        for (event in registeredEvents.keys()) {
            off(event)
        }
    }

    fun off(event: String) {
        socket.off(event)
    }
}