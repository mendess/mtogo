package xyz.mendess.mtogo.viewmodels

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val PLAYLIST_URL =
    "https://raw.githubusercontent.com/mendess/spell-book/master/runes/m/playlist"

sealed interface PlaylistLoadingState {
    data object Loading : PlaylistLoadingState
    data class Success(val playlist: Playlist) : PlaylistLoadingState
    data class Error(val throwable: Throwable) : PlaylistLoadingState
}

suspend fun fetchPlaylist(http: HttpClient): Result<Playlist> {
    return runCatching {
        Playlist.fromStr(http.get(PLAYLIST_URL).bodyAsText())
    }
}

class PlaylistViewModel(default: List<Playlist.Song> = emptyList()) : ViewModel() {
    private val _playlistFlow = MutableStateFlow(
        if (default.isEmpty()) {
            PlaylistLoadingState.Loading
        } else {
            PlaylistLoadingState.Success(Playlist(default))
        }
    )

    val playlistFlow
        get() = _playlistFlow.asStateFlow()

    init {
        if (playlistFlow.value is PlaylistLoadingState.Loading) {
            viewModelScope.launch {
                _playlistFlow.value = HttpClient(CIO)
                    .use { fetchPlaylist(it) }
                    .fold(
                        onSuccess = PlaylistLoadingState::Success,
                        onFailure = PlaylistLoadingState::Error,
                    )
            }
        }
    }

    suspend fun get(): Playlist {
        return playlistFlow.filterIsInstance<PlaylistLoadingState.Success>().first().playlist
    }
}

data class Playlist(val songs: List<Song>) {
    fun findByName(query: String): Song? = songs.find { it.title == query }
    fun findById(id: VideoId): Song? = songs.find { it.id == id }

    companion object {
        fun fromStr(s: String) = Playlist(
            songs = s.split('\n')
                .asIterable()
                .mapNotNull c@{ line ->
                    val fields = line.split('\t')
                    Song(
                        title = fields.getOrNull(0) ?: return@c null,
                        id = VideoId.fromUrl(fields.getOrNull(1) ?: return@c null),
                        duration = fields.getOrNull(2)?.toLong() ?: return@c null,
                        categories = fields.slice(3..<fields.size).sorted()
                    )
                }
                .reversed()
        )
    }

    val categories by lazy {
        val map = HashMap<String, MutableList<Song>>()
        for (s in songs) {
            for (c in s.categories) {
                map.getOrPut(c) { mutableListOf() }.add(s)
            }
        }
        map.asIterable().map { it.key to it.value }.sortedBy { -it.second.size }
    }

    data class Song(
        val title: String,
        val id: VideoId,
        val duration: Long,
        val categories: List<String>,
    )
}

@JvmInline
value class VideoId(private val id: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString()!!)

    fun get(): String = id

    fun toAudioUri(): Uri =
        Uri.parse("https://mendess.xyz/api/v1/playlist/audio/${id}")

    fun toThumbnailUri(): Uri =
        Uri.parse("https://mendess.xyz/api/v1/playlist/thumb/${id}")

    fun toMetadataUri(): Uri =
        Uri.parse("https://mendess.xyz/api/v1/playlist/metadata/${id}")

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<VideoId> {
        fun fromUrl(s: String): VideoId {
            return VideoId(Uri.parse(s).path!!.trimStart('/'))
        }

        override fun createFromParcel(parcel: Parcel): VideoId {
            return VideoId(parcel)
        }

        override fun newArray(size: Int): Array<VideoId?> {
            return arrayOfNulls(size)
        }
    }
}
