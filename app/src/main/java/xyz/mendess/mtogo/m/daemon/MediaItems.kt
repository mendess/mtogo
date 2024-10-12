@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.m.daemon

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.data.CachedMusic
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.util.orelse
import xyz.mendess.mtogo.util.parcelable
import xyz.mendess.mtogo.viewmodels.Playlist
import xyz.mendess.mtogo.viewmodels.VideoId
import xyz.mendess.mtogo.viewmodels.fetchPlaylist
import java.io.Closeable
import java.net.URLEncoder

private const val YT_SHORT_HOST = "youtu.be"
private const val YT_LONG_HOST = "youtube.com"
private const val YT_SHORTS_PATH = "shorts"
private const val YT_WATCH_PATH = "watch"

private val JSON = Json { ignoreUnknownKeys = true }

class MediaItems(settings: Settings, scope: CoroutineScope, context: Context) : Closeable {
    companion object {
        const val MUSIC_METADATA_CATEGORIES = "cat"
        const val MUSIC_METADATA_THUMBNAIL_ID = "thumb"
        const val MUSIC_METADATA_SHOULD_CACHE_WITH = "cache-with"
    }

    private val http = HttpClient(CIO) {
        install(HttpTimeout)
    }
    private val playlist = scope.async(start = CoroutineStart.LAZY) {
        fetchPlaylist(http)
    }
    private val cachedMusic = CachedMusic(settings, http, scope, context)

    override fun close() {
        http.closeQuietly()
    }

    suspend fun fromParcelable(
        context: Context,
        mediaItem: ParcelableMediaItem,
        caching: Boolean,
    ): MediaItem {
        return when (mediaItem) {
            is ParcelableMediaItem.PlaylistItem -> fromVideoId(context, mediaItem.id, caching)
            is ParcelableMediaItem.Url -> fromUrl(context, mediaItem.uri, caching)
        }
    }

    suspend fun parcelableFromSearch(query: String): Result<ParcelableMediaItem> {
        return if (query.startsWith("http")) {
            Result.success(ParcelableMediaItem.Url(Uri.parse(query)))
        } else {
            val query = withContext(Dispatchers.IO) { URLEncoder.encode(query, "utf-8") }
            runCatching {
                val resp = http.get("https://mendess.xyz/api/v1/playlist/search/${query}")
                val id = resp.bodyAsText()
                ParcelableMediaItem.PlaylistItem(VideoId(id))
            }
        }
    }

    private suspend fun fromVideoId(context: Context, id: VideoId, caching: Boolean): MediaItem {
        return playlist.await()
            .map { it.findById(id)?.let { song -> fromPlaylistSong(context, song, caching) } }
            .getOrNull()
            ?: run {
                val titleUri = id.toMetadataUri()
                val title = try {
                    @Serializable
                    data class Metadata(val title: String)

                    val response = http.get(titleUri.toString())
                    JSON.decodeFromString<Metadata>(response.bodyAsText()).title
                } catch (e: Exception) {
                    "error getting title: ${e.message}"
                }
                makeMediaItem(
                    uri = id.toAudioUri(),
                    title = title,
                    thumbnailUri = id.toThumbnailUri()
                )
            }
    }

    private suspend fun fromUrl(context: Context, uri: Uri, caching: Boolean): MediaItem {
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
        return vidId?.let { fromVideoId(context, it, caching) } ?: makeMediaItem(uri = uri)
    }

    private suspend fun fromPlaylistSong(
        context: Context,
        song: Playlist.Song,
        caching: Boolean
    ): MediaItem {
        return if (caching) {
            cachedMusic.fetchCachedSong(context, song)?.toMediaItemOf(song)
        } else {
            null
        } ?: makeMediaItem(
            uri = song.id.toAudioUri(),
            thumbnailUri = song.id.toThumbnailUri(),
            title = song.title,
            categories = song.categories,
            shouldCacheWith = song.id,
        )
    }

    suspend fun caching(context: Context, item: MediaItem): MediaItem? {
        val id = item
            .mediaMetadata
            .extras
            ?.parcelable<VideoId>(MUSIC_METADATA_SHOULD_CACHE_WITH)
            ?: return null
        val song = playlist.await().orelse { return null }.findById(id) ?: return null
        return cachedMusic.fetchCachedSong(context, song)?.toMediaItemOf(song)
    }
}

fun CachedMusic.Item.toMediaItemOf(song: Playlist.Song): MediaItem = makeMediaItem(
    uri = audio,
    thumbnailUri = thumb,
    title = song.title,
    categories = song.categories,
)

fun makeMediaItem(
    uri: Uri,
    title: String? = null,
    thumbnailUri: Uri? = null,
    shouldCacheWith: VideoId? = null,
    categories: List<String> = emptyList(),
): MediaItem {
    return MediaItem.Builder().run {
        setMediaMetadata(MediaMetadata.Builder().run {
            if (title != null) setTitle(title)
            setUri(uri)
            setExtras(Bundle().apply {
                if (shouldCacheWith != null) putParcelable(
                    MediaItems.MUSIC_METADATA_SHOULD_CACHE_WITH,
                    shouldCacheWith
                )
                if (categories.isNotEmpty()) putStringArrayList(
                    MediaItems.MUSIC_METADATA_CATEGORIES,
                    ArrayList(categories)
                )
                if (thumbnailUri != null) putString(
                    MediaItems.MUSIC_METADATA_THUMBNAIL_ID,
                    thumbnailUri.toString(),
                )
            })
            build()
        })
        setUri(uri)
        build()
    }
}
