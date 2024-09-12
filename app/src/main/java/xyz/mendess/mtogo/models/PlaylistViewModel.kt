package xyz.mendess.mtogo.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.create
import retrofit2.http.GET

const val PLAYLIST_URL = "https://raw.githubusercontent.com"

interface FilhaDaPutaDaInterface {
    @GET("/mendess/spell-book/master/runes/m/playlist")
    fun download(): Call<ResponseBody>
}

private val playlistDownload = Retrofit.Builder()
    .baseUrl(PLAYLIST_URL)
    .build()
    .create<FilhaDaPutaDaInterface>()

sealed interface PlaylistLoadingState {
    data object Loading : PlaylistLoadingState
    data class Success(val playlist: Playlist) : PlaylistLoadingState
    data class Error(val throwable: Throwable) : PlaylistLoadingState
}

class PlaylistViewModel(default: List<Playlist.Song> = emptyList()) : ViewModel() {
    private val _playlistFlow: MutableStateFlow<PlaylistLoadingState> =
        MutableStateFlow(
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
                playlistDownload.download().enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(
                        call: Call<ResponseBody>,
                        response: Response<ResponseBody>
                    ) {
                        _playlistFlow.value = PlaylistLoadingState.Success(
                            Playlist.fromStr(response.body()?.string() ?: "")
                        )
                    }

                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        _playlistFlow.value = PlaylistLoadingState.Error(t)
                    }
                })
            }
        }
    }


}

data class Playlist(val songs: List<Song>) {
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
                        categories = fields.slice(3..<fields.size)
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

    @JvmInline
    value class VideoId(private val id: String) {
        fun toAudioUri(): Uri =
            Uri.parse("https://mendess.xyz/api/v1/playlist/audio/${id}")

        fun toThumbnailUri(): Uri =
            Uri.parse("https://mendess.xyz/api/v1/playlist/thumb/${id}")

        companion object {
            fun fromUrl(s: String): VideoId {
                return VideoId(Uri.parse(s).path!!.trimStart('/'))
            }
        }
    }
}
