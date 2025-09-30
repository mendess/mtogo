package xyz.mendess.mtogo.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import m_to_go.app.BuildConfig
import xyz.mendess.mtogo.util.Err
import xyz.mendess.mtogo.util.Ok
import xyz.mendess.mtogo.util.orelse
import xyz.mendess.mtogo.util.then
import xyz.mendess.mtogo.util.withPermits
import xyz.mendess.mtogo.viewmodels.BangerId
import xyz.mendess.mtogo.viewmodels.Playlist
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val concurrentDownloads = Semaphore(permits = 4)

class CachedMusic(
    private val settings: Settings,
    private val http: HttpClient,
    scope: CoroutineScope,
    context: Context,
    val sendError: (Throwable) -> Unit
) : Closeable {
    companion object {
        val cachedFileNameRegex = "=[A-Za-z0-9_\\-]{11}=m(|art)\\.[a-z]{3,5}$".toRegex()
        private val cachedDownloadingFileNameRegex =
            "=[A-Za-z0-9_\\-]{11}=m(|art)\\.[a-z]{3,5}.downloading".toRegex()
    }

    init {
        scope.launch {
            settings.cacheMusicDir.mapNotNull { it.uri }.collect {
                concurrentDownloads.withPermits(4U) {
                    val tree = DocumentFile.fromTreeUri(context, it) ?: return@collect
                    for (f in tree.listFiles()) {
                        if (f.name?.contains(cachedDownloadingFileNameRegex) == true) {
                            val name = f.name
                            if (f.delete()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "deleted $name", Toast.LENGTH_LONG)
                                        .show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    data class Item(
        val audio: Uri,
        val thumb: Uri?,
    )

    private val counter: AtomicInteger = AtomicInteger(0)
    private val pool = Executors.newFixedThreadPool(4) {
        Thread(it, "cached-music-thread ${counter.incrementAndGet()}")
    }.asCoroutineDispatcher()

    suspend fun fetchCachedSong(context: Context, song: Playlist.Song): Result<Item?> {
        return withContext(pool) {
            val (audio, thumb) = loadById(context, song.id)
            if (audio == null) {
                concurrentDownloads.withPermit {
                    storeByVideoId(context, song, thumb)
                }
            } else {
                Ok(Item(audio, thumb))
            }
        }
    }

    override fun close() {
        pool.close()
    }

    private fun loadById(context: Context, id: BangerId): Pair<Uri?, Uri?> {
        val (dir, useThumbNail) = settings.cacheMusicDir.value.intoParts() ?: return null to null
        var audio: Uri? = null
        var thumb: Uri? = null
        for (f in DocumentFile.fromTreeUri(context, dir)!!.listFiles()) {
            if (audio != null && if (useThumbNail) thumb != null else true) break
            if (f.isDirectory) continue
            val name = f.name ?: continue
            if (name.contains(id.get())) {
                if (name.contains("=m.")) {
                    audio = f.uri
                } else if (name.contains("=mart.")) {
                    thumb = f.uri
                }
            }
        }
        return audio to thumb
    }

    private suspend fun storeByVideoId(
        context: Context,
        song: Playlist.Song,
        preCachedThumb: Uri?
    ): Result<Item?> {
        Log.i("CachedMusic", "storeByVideoId ${song.name}")
        val (musicDirUri, useThumb) = settings.cacheMusicDir.value.intoParts()
            ?: return Ok(null).also {
                Log.i("CachedMusic", "returning 1")
            }
        val root = DocumentFile.fromTreeUri(context, musicDirUri) ?: run {
            return Ok(null).also {
                Log.i("CachedMusic", "returning 2")
            }
        }

        val (thumbResponse, audioResponse) = coroutineScope {
            val thumbResponse = useThumb.then {
                async {
                    (preCachedThumb == null).then { makeHttpRequest(song.id.toThumbnailUri()) }
                }
            }
            val audioResponse = async { makeHttpRequest(song.id.toAudioUri()) }
            kotlin.runCatching {
                val thumb = thumbResponse?.await()?.getOrThrow()
                val audio = audioResponse.await().getOrThrow()
                thumb to audio
            }
        }.orelse {
            withContext(Dispatchers.Main) {
                val msg = "failed to download ${song.name}"
                sendError(RuntimeException(msg, it))
                Toast.makeText(context, "$msg: ${it.message ?: it.toString()}", Toast.LENGTH_LONG)
                    .show()
            }
            Log.i("CachedMusic", "returning 3 ${it.message}")
            return Err(it)
        }

        val audioUri = audioResponse.contentType("audio/ogg").let { (mimeType, ext) ->
            root.write(
                context,
                mimeType,
                "${song.name}=${song.id.get()}=m.${ext}",
                audioResponse.bodyAsChannel().toInputStream()
            )
        }
        val thumbUri = thumbResponse?.contentType("image/webp")?.let { (mimeType, ext) ->
            root.write(
                context,
                mimeType,
                "${song.name}=${song.id.get()}=mart.${ext}",
                thumbResponse.bodyAsChannel().toInputStream()
            )
        } ?: preCachedThumb

        return Ok(if (audioUri != null) Item(audioUri, thumbUri) else null).also {
            Log.i("CachedMusic", "returning 4 $it")
        }
    }

    private fun DocumentFile.write(
        context: Context,
        mimeType: String,
        name: String,
        inputStream: InputStream
    ): Uri? {
        val songFile = createFile(mimeType, "$name.downloading") ?: run {
            return null
        }
        context.contentResolver.openOutputStream(songFile.uri)?.use { out ->
            inputStream.use { ins -> ins.copyTo(out) }
        }
        if (!songFile.renameTo(name)) return null
        return songFile.uri
    }

    private suspend fun makeHttpRequest(uri: Uri): Result<HttpResponse> {
        return retry(5U) {
            http.get(uri.toString()) {
                bearerAuth(BuildConfig.BACKEND_TOKEN)
                timeout {
                    this.connectTimeoutMillis = 1_000L
                    this.requestTimeoutMillis = 20_000L
                }
            }
        }.mapCatching {
            if (it.status.isSuccess()) {
                it
            } else {
                throw RuntimeException("$it: request failed")
            }
        }
    }
}

private data class ContentType(val mimeType: String, val ext: String)

private fun HttpResponse.contentType(default: String): ContentType {
    val mimeType = headers["Content-Type"] ?: default
    val ext = mimeType.split('/')[1]
    return ContentType(mimeType, ext)
}

private suspend fun <T> retry(num: UInt, op: suspend () -> T): Result<T> {
    for (i in 1U..num) {
        val r = runCatching {
            op()
        }
        if (r.isSuccess || i == num) return r
    }
    throw NotImplementedError("unreachable")
}

private fun CacheModeSettings.intoParts(): Pair<Uri, Boolean>? {
    return when (val mode = this) {
        CacheModeSettings.Disabled -> null
        is CacheModeSettings.MusicOnly -> mode.uri to false
        is CacheModeSettings.Full -> mode.uri to true
    }
}