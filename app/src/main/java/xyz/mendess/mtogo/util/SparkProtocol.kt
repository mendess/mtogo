package xyz.mendess.mtogo.util

import androidx.annotation.FloatRange
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds


interface IntoSparkResponse {
    fun into(): Spark.Response
}

interface ToJSONObject {
    fun toJSONObject(): Any
}

class SparkDeserializationError : Exception {
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(message: String) : super(message)
}

object Spark {
    sealed interface Command {
        data object Reload : Command

        data object Heartbeat : Command

        data object Version : Command

        data class Music(val music: MusicCmd) : Command

        companion object {
            fun fromJsonObject(obj: Any): Command = try {
                when (obj) {
                    "Reload" -> Reload
                    "Heartbeat" -> Heartbeat
                    "Version" -> Version
                    is JSONObject -> Music(MusicCmd.fromJsonObject(obj.getJSONObject("Music")))
                    else -> throw SparkDeserializationError("not a valid json $obj")
                }
            } catch (e: JSONException) {
                throw SparkDeserializationError("not a valid json $obj", e)
            }
        }
    }

    data class MusicCmd(
        val command: MusicCmdKind,
        val index: Int?,
        val username: String?
    ) {
        companion object {
            fun fromJsonObject(obj: JSONObject): MusicCmd = MusicCmd(
                command = MusicCmdKind.fromJSONObject(obj.get("command")),
                index = obj.nullableGet("index", JSONObject::getInt),
                username = obj.nullableGet("username", JSONObject::getString)
            )
        }
    }

    sealed interface MusicCmdKind {
        data object Frwd : MusicCmdKind

        data object Back : MusicCmdKind

        data object CyclePause : MusicCmdKind

        data class ChangeVolume(val amount: Int) : MusicCmdKind

        data object Current : MusicCmdKind

        data class Queue(val query: String, val search: Boolean) : MusicCmdKind

        data class Now(val amount: UInt?) : MusicCmdKind

        companion object {
            fun fromJSONObject(obj: Any): MusicCmdKind = when (obj) {
                "Frwd" -> Frwd
                "Back" -> Back
                "CyclePause" -> CyclePause
                "Current" -> Current
                is JSONObject -> when (val key: Any? = obj.keys().next()) {
                    "ChangeVolume" -> with(obj.getJSONObject("ChangeVolume")) {
                        ChangeVolume(amount = getInt("amount"))
                    }

                    "Queue" -> with(obj.getJSONObject("Queue")) {
                        Queue(query = getString("query"), search = getBoolean("search"))
                    }

                    "Now" -> with(obj.getJSONObject("Now")) {
                        Now(amount = nullableGet("amount", JSONObject::getInt)?.toUInt())
                    }

                    else -> throw SparkDeserializationError("$obj is not a valid MusicCmdKind")
                }

                else -> throw SparkDeserializationError("$obj is not a valid MusicCmdKind")
            }
        }
    }

    sealed interface Response : IntoSparkResponse, ToJSONObject {
        data class Ok(val success: SuccessfulResponse) : Response {
            override fun toJSONObject() = JSONObject().apply {
                put("Ok", success.toJSONObject())
            }
        }

        data class Err(val error: ErrorResponse) : Response {
            override fun toJSONObject() = JSONObject().apply {
                put("Err", error.toJSONObject())
            }
        }

        override fun into(): Response = this
    }

    sealed interface SuccessfulResponse : IntoSparkResponse, ToJSONObject {
        data object Unit : SuccessfulResponse {
            override fun toJSONObject() = "Unit"
        }

        data class Version(val version: String) : SuccessfulResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("Version", version)
            }
        }

        data class Music(val response: MusicResponse) : SuccessfulResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("MusicResponse", response.toJSONObject())
            }
        }

        override fun into(): Response = Response.Ok(this)
    }

    sealed interface MusicResponse : IntoSparkResponse, ToJSONObject {
        data class Title(val title: String) : MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("Title", JSONObject().apply {
                    put("title", title)
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = Title(title = obj.getString("title"))
            }
        }

        data class PlayState(val paused: Boolean) : MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("PlayState", JSONObject().apply {
                    put("paused", paused)
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = PlayState(paused = obj.getBoolean("paused"))
            }
        }

        data class Volume(
            @FloatRange(from = 0.0, to = 100.0)
            val volume: Double
        ) : MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("Volume", JSONObject().apply {
                    put("volume", volume)
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = Volume(volume = obj.getDouble("volume"))
            }
        }

        data class Current(
            val title: String,
            val chapter: Pair<UInt, String>?,
            val playing: Boolean,
            @FloatRange(from = 0.0, to = 100.0)
            val volume: Double,
            val progress: Double?,
            val playbackTime: Duration?,
            val duration: Duration,
            val categories: List<String>,
            val index: UInt,
            val next: String?,
        ) : MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("Current", JSONObject().apply {
                    put("title", title)
                    put("chapter", chapter)
                    put("playing", playing)
                    put("volume", volume)
                    put("progress", progress)
                    put("playback_time", playbackTime?.toJSONObject())
                    put("duration", duration.toJSONObject())
                    put("categories", categories.toJSONArray())
                    put("index", index.toInt())
                    put("next", next)
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = Current(
                    title = obj.getString("title"),
                    chapter = if (obj.has("chapter")) {
                        val chapter = obj.getJSONArray("chapter")
                        chapter.getInt(0).toUInt() to chapter.getString(1)
                    } else {
                        null
                    },
                    playing = obj.getBoolean("playing"),
                    volume = obj.getDouble("volume"),
                    progress = obj.getDouble("progress"),
                    playbackTime = if (obj.has("progress")) durationFromJSONObject(
                        obj.getJSONObject(
                            "progress"
                        )
                    ) else null,
                    duration = durationFromJSONObject(obj.getJSONObject("duration")),
                    categories = jsonArrayToListString(obj.getJSONArray("categories")),
                    index = obj.getInt("index").toUInt(),
                    next = if (obj.has("next")) obj.getString("next") else null
                )

            }
        }

        data class QueueSummary(val from: UInt, val movedTo: UInt, val current: UInt) :
            MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("QueueSummary", JSONObject().apply {
                    put("from", from.toInt())
                    put("moved_to", movedTo.toInt())
                    put("current", current.toInt())
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = QueueSummary(
                    from = obj.getInt("from").toUInt(),
                    movedTo = obj.getInt("moved_to").toUInt(),
                    current = obj.getInt("current").toUInt(),
                )
            }
        }

        data class Now(val before: List<String>, val current: String, val after: List<String>) :
            MusicResponse {
            override fun toJSONObject() = JSONObject().apply {
                put("Now", JSONObject().apply {
                    put("before", before.toJSONArray())
                    put("current", current)
                    put("after", after.toJSONArray())
                })
            }

            companion object {
                fun fromJSONObject(obj: JSONObject) = Now(
                    before = jsonArrayToListString(obj.getJSONArray("before")),
                    current = obj.getString("current"),
                    after = jsonArrayToListString(obj.getJSONArray("after")),
                )
            }
        }

//        fun fromJSONObject(obj: JSONObject): MusicResponse = when (val key = obj.keys().next()) {
//            "Title" -> Title.fromJSONObject(obj.getJSONObject(key))
//            "PlayState" -> PlayState.fromJSONObject(obj.getJSONObject(key))
//            "Volume" -> Volume.fromJSONObject(obj.getJSONObject(key))
//            "Current" -> Current.fromJSONObject(obj.getJSONObject(key))
//            "QueueSummary" -> QueueSummary.fromJSONObject(obj.getJSONObject(key))
//            "Now" -> Now.fromJSONObject(obj.getJSONObject(key))
//            else -> throw SparkDeserializationError("$key is not a valid Music response")
//        }

        override fun into(): Response = Response.Ok(SuccessfulResponse.Music(this))
    }

    sealed class ErrorResponse : IntoSparkResponse, ToJSONObject {
        data class DeserializingCommand(val e: String) : ErrorResponse() {
            override fun toJSONObject() = JSONObject().apply {
                put("DeserializingCommand", e)
            }
        }

        data class ForwardedError(val e: String) : ErrorResponse() {
            override fun toJSONObject() = JSONObject().apply {
                put("ForwardedError", e)
            }
        }

        data class RequestFailed(val e: String) : ErrorResponse() {
            override fun toJSONObject() = JSONObject().apply {
                put("RequestFailed", e)
            }
        }

        data class IoError(val e: String) : ErrorResponse() {
            override fun toJSONObject() = JSONObject().apply {
                put("IoError", e)
            }
        }

        data class RelayError(val e: String) : ErrorResponse() {
            override fun toJSONObject() = JSONObject().apply {
                put("RelayError", e)
            }
        }

        override fun into(): Response = Response.Err(this)
    }
}

private fun jsonArrayToListString(array: JSONArray) = (0..<array.length()).map(array::getString)
private fun List<String>.toJSONArray() = JSONArray().also {
    for (s in this) {
        it.put(s)
    }
}

private fun durationFromJSONObject(obj: JSONObject): Duration =
    obj.getInt("seconds").seconds + obj.getInt("seconds").nanoseconds

private fun Duration.toJSONObject() = JSONObject().apply {
    put("secs", inWholeSeconds)
    put("nanos", inWholeNanoseconds % 1_000_000_000)
}

private fun <T> JSONObject.nullableGet(key: String, by: JSONObject.(String) -> T): T? {
    return if (has(key)) {
        if (isNull(key)) {
            null
        } else by(key)
    } else {
        null
    }
}