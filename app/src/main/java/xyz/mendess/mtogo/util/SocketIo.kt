package xyz.mendess.mtogo.util

import android.net.Uri
import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SocketIo(uri: Uri, hostname: String) {
    private val registeredEvents: ConcurrentHashMap<String, Unit> = ConcurrentHashMap()

    private val socket: Socket = run {
        Log.d("BackendViewModel", "connecting socket to $uri")

        IO.socket("${uri}/spark-protocol?h=$hostname").connect().apply {
            on(Socket.EVENT_CONNECT) { Log.d("BackendViewModel", "Socket connected!") }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.d("BackendViewModel", "connection failed: ${args.contentDeepToString()}")
            }
            io().on(Manager.EVENT_ERROR) { args ->
                Log.d(
                    "BackendViewModel",
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