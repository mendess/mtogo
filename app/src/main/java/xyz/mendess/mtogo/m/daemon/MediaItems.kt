@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.m.daemon

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import m_to_go.app.BuildConfig
import okhttp3.internal.closeQuietly
import xyz.mendess.mtogo.data.CachedMusic
import xyz.mendess.mtogo.data.Settings
import xyz.mendess.mtogo.util.orelse
import xyz.mendess.mtogo.util.parcelable
import xyz.mendess.mtogo.viewmodels.BangerId
import xyz.mendess.mtogo.viewmodels.Playlist
import xyz.mendess.mtogo.viewmodels.PlaylistViewModel
import xyz.mendess.mtogo.viewmodels.VideoId
import java.io.Closeable
import java.net.URLEncoder

private const val YT_SHORT_HOST = "youtu.be"
private const val YT_LONG_HOST = "youtube.com"
private const val YT_SHORTS_PATH = "shorts"
private const val YT_WATCH_PATH = "watch"

private val JSON = Json { ignoreUnknownKeys = true }

class MediaItems(
    settings: Settings,
    scope: CoroutineScope,
    context: Context,
    val sendError: (Throwable) -> Unit
) : Closeable {
    companion object {
        const val MUSIC_METADATA_CATEGORIES = "cat"
        const val MUSIC_METADATA_LANGUAGE = "language"
        const val MUSIC_METADATA_LIKED_BY = "liked-by"
        const val MUSIC_METADATA_RECOMMENDED_BY = "recommended-by"
        const val MUSIC_METADATA_THUMBNAIL_ID = "thumb"
        const val MUSIC_METADATA_SHOULD_CACHE_WITH = "cache-with"
    }

    private val http = HttpClient(CIO) {
        install(HttpTimeout)
    }
    private val playlist = scope.async(start = CoroutineStart.LAZY) {
        PlaylistViewModel(context).tryGet()
    }
    private val cachedMusic = CachedMusic(settings, http, scope, context, sendError)

    override fun close() {
        http.closeQuietly()
    }

    suspend fun fromParcelable(
        context: Context,
        mediaItem: ParcelableMediaItem,
        caching: Boolean,
    ): MediaItem {
        return when (mediaItem) {
            is ParcelableMediaItem.YoutubeItem -> fromVideoId(mediaItem.id)
            is ParcelableMediaItem.PlaylistItem -> fromBangerId(context, mediaItem.id, caching)
            is ParcelableMediaItem.Url -> fromUrl(mediaItem.uri)
        }
    }

    suspend fun parcelableFromSearch(query: String): Result<ParcelableMediaItem> {
        return if (query.startsWith("http")) {
            Result.success(ParcelableMediaItem.Url(query.toUri()))
        } else {
            val query = withContext(Dispatchers.IO) { URLEncoder.encode(query, "utf-8") }
            runCatching {
                val resp = http.get("${BuildConfig.OLD_MUSIC_BACKEND}/api/v1/playlist/search/${query}") {
                    bearerAuth(BuildConfig.BACKEND_TOKEN)
                }
                val id = resp.bodyAsText()
                ParcelableMediaItem.YoutubeItem(VideoId(id))
            }
        }
    }

    private suspend fun fromBangerId(context: Context, id: BangerId, caching: Boolean): MediaItem {
        return playlist.await()
            .map { it.findById(id)?.let { song -> fromPlaylistSong(context, song, caching) } }
            .getOrThrow()
            ?: throw RuntimeException("failed to find $id in playlist")
    }

    private suspend fun fromVideoId(id: VideoId): MediaItem {
        val titleUri = id.toMetadataUri()
        val title = try {
            @Serializable
            data class Metadata(val title: String)

            val response = http.get(titleUri.toString()) {
                bearerAuth(BuildConfig.BACKEND_TOKEN)
            }
            JSON.decodeFromString<Metadata>(response.bodyAsText()).title
        } catch (e: Exception) {
            "error getting title: ${e.message}"
        }
        return makeMediaItem(
            uri = id.toAudioUri(),
            title = title,
            thumbnailUri = id.toThumbnailUri()
        )
    }

    private suspend fun fromUrl(uri: Uri): MediaItem {
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
        return vidId?.let { fromVideoId(it) } ?: makeMediaItem(uri = uri)
    }

    private suspend fun fromPlaylistSong(
        context: Context,
        song: Playlist.Song,
        caching: Boolean
    ): MediaItem {
        return if (caching) {
            cachedMusic
                .fetchCachedSong(context, song)
                .getOrElse { sendError(it); null }
                ?.toMediaItemOf(song)
        } else {
            null
        } ?: makeMediaItem(
            uri = song.id.toAudioUri(),
            thumbnailUri = song.id.toThumbnailUri(),
            title = song.name,
            categories = song.categories,
            shouldCacheWith = song.id,
            artist = song.artist,
            genres = song.genres,
            language = song.language,
            likedBy = song.likedBy,
            recommendedBy = song.recommendedBy
        )
    }

    suspend fun caching(context: Context, item: MediaItem): MediaItem? {
        val id = item
            .mediaMetadata
            .extras
            ?.parcelable<BangerId>(MUSIC_METADATA_SHOULD_CACHE_WITH)
            ?: return null
        val song = playlist.await().orelse { return null }.findById(id) ?: return null
        return cachedMusic
            .fetchCachedSong(context, song)
            .getOrElse { sendError(it); return null }
            ?.toMediaItemOf(song)
    }
}

fun CachedMusic.Item.toMediaItemOf(song: Playlist.Song): MediaItem = makeMediaItem(
    uri = audio,
    thumbnailUri = thumb ?: song.id.toThumbnailUri(),
    title = song.name,
    categories = song.categories.toList(),
    artist = song.artist,
    genres = song.genres,
    language = song.language,
    likedBy = song.likedBy,
    recommendedBy = song.recommendedBy
)

private fun makeMediaItem(
    uri: Uri,
    title: String? = null,
    thumbnailUri: Uri? = null,
    shouldCacheWith: BangerId? = null,
    categories: List<String> = emptyList(),
    artist: String? = null,
    genres: List<String> = emptyList(),
    language: String? = null,
    likedBy: List<String> = emptyList(),
    recommendedBy: String? = null,
): MediaItem {
    return MediaItem.Builder().run {
        setMediaMetadata(MediaMetadata.Builder().run {
            if (title != null) setTitle(title)
            setUri(uri)
            if (artist != null) {
                setArtist(artist)
                setAlbumArtist(artist)
            }
            if (genres.isNotEmpty()) setGenre(genres.first())
            if (thumbnailUri != null) setArtworkUri(thumbnailUri)
            setExtras(Bundle().apply {
                if (shouldCacheWith != null) putParcelable(
                    MediaItems.MUSIC_METADATA_SHOULD_CACHE_WITH,
                    shouldCacheWith
                )
                if (categories.isNotEmpty()) putStringArrayList(
                    MediaItems.MUSIC_METADATA_CATEGORIES,
                    ArrayList(categories)
                )
                if (language != null) putString(MediaItems.MUSIC_METADATA_LANGUAGE, language)
                if (likedBy.isNotEmpty()) putStringArrayList(
                    MediaItems.MUSIC_METADATA_LIKED_BY,
                    ArrayList(likedBy)
                )
                if (recommendedBy != null) putString(
                    MediaItems.MUSIC_METADATA_RECOMMENDED_BY,
                    recommendedBy
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
