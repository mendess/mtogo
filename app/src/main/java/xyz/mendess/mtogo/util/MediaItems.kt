@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.util

import android.net.Uri
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.m.ParcelableMediaItem
import xyz.mendess.mtogo.viewmodels.Playlist
import xyz.mendess.mtogo.viewmodels.VideoId
import java.io.Closeable
import java.net.URLEncoder

private const val YT_SHORT_HOST = "youtu.be"
private const val YT_LONG_HOST = "youtube.com"
private const val YT_SHORTS_PATH = "shorts"
private const val YT_WATCH_PATH = "watch"

private val JSON = Json { ignoreUnknownKeys = true }

class MediaItems : Closeable {
    companion object {
        const val MUSIC_METADATA_CATEGORIES = "cat"
        const val MUSIC_METADATA_THUMBNAIL_ID = "thumb"
    }

    val http = HttpClient(CIO)

    override fun close() {
        http.closeQuietly()
    }

    suspend fun fromSearch(query: String): Result<ParcelableMediaItem> {
        Log.d("MediaItems", "queueing search $query")
        val query = withContext(Dispatchers.IO) { URLEncoder.encode(query, "utf-8") }
        return runCatching {
            val resp = http.get("https://mendess.xyz/api/v1/playlist/search/${query}")
            val id = resp.bodyAsText()
            Log.d("MediaItems", "id: $id")
            fromVideoId(VideoId(id))
        }
    }

    private suspend fun fromVideoId(id: VideoId): ParcelableMediaItem {
        val audioUri = id.toAudioUri()
        val titleUri = id.toMetadataUri()
        val title = try {
            @Serializable
            data class Metadata(val title: String)

            val response = http.get(titleUri.toString())
            JSON.decodeFromString<Metadata>(response.bodyAsText()).title
        } catch (e: Exception) {
            "error getting title: $e"
        }
        return ParcelableMediaItem(
            uri = audioUri,
            title = title,
            thumbnailUri = id.toThumbnailUri()
        )
    }

    suspend fun fromUrl(url: String): ParcelableMediaItem {
        val uri = Uri.parse(url)
        val vidId = if (uri.host?.contains(YT_SHORT_HOST) == true) {
            uri.path?.let(::VideoId)
        } else if (uri.host?.contains(YT_LONG_HOST) == true) {
            when (uri.pathSegments.firstOrNull()) {
                YT_SHORTS_PATH -> uri.pathSegments.lastOrNull()?.let(::VideoId)
                YT_WATCH_PATH -> uri.getQueryParameter("v")?.let(::VideoId)
                else -> null
            }
        } else {
            null
        }
        return vidId?.let { fromVideoId(it) } ?: ParcelableMediaItem(uri = uri)
    }

    fun fromPlaylistSong(song: Playlist.Song): ParcelableMediaItem = ParcelableMediaItem(
        uri = song.id.toAudioUri(),
        thumbnailUri = song.id.toThumbnailUri(),
        title = song.title,
        categories = song.categories
    )
}