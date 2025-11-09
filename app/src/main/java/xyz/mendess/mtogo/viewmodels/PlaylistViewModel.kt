package xyz.mendess.mtogo.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import m_to_go.app.BuildConfig
import xyz.mendess.mtogo.util.dataStore
import java.net.URL
import kotlin.time.Duration.Companion.seconds

private const val PLAYLIST_URL = "${BuildConfig.MUSIC_BACKEND}/playlist"

private val SAVED_STATE_PLAYLIST = stringPreferencesKey("playlist-cache")

sealed interface PlaylistLoadingState {
    data object Loading : PlaylistLoadingState
    data class Success(val playlist: Playlist) : PlaylistLoadingState
    data class Error(val throwable: Throwable) : PlaylistLoadingState
}

private val JSON = Json { ignoreUnknownKeys = true }

class PlaylistViewModel(val context: Context, default: List<Playlist.Song> = emptyList()) :
    ViewModel() {
    private val dataStore = context.dataStore

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
                HttpClient(CIO) {
                    install(HttpTimeout)
                    install(ContentNegotiation) {
                        json(JSON)
                        register(ContentType.Text.Plain, KotlinxSerializationConverter(Json))
                    }
                }.use { http ->
                    while (_playlistFlow.value !is PlaylistLoadingState.Success) {
                        _playlistFlow.value = fetchPlaylist(http)
                            .fold(
                                onSuccess = PlaylistLoadingState::Success,
                                onFailure = PlaylistLoadingState::Error,
                            )

                        delay(1.seconds)
                    }
                }
            }
        }
    }

    suspend fun get(): Playlist {
        return playlistFlow.filterIsInstance<PlaylistLoadingState.Success>().first().playlist
    }

    suspend fun tryGet(): Result<Playlist> {
        return playlistFlow.mapNotNull {
            when (it) {
                PlaylistLoadingState.Loading -> null
                is PlaylistLoadingState.Success -> Result.success(it.playlist)
                is PlaylistLoadingState.Error -> Result.failure(it.throwable)
            }
        }.first()
    }

    private suspend fun fetchPlaylist(http: HttpClient): Result<Playlist> {
        return runCatching {
            try {
                val playlist = http.get(PLAYLIST_URL) {
                    timeout {
                        this.connectTimeoutMillis = 10_000L
                        this.requestTimeoutMillis = 12_000L
                        this.socketTimeoutMillis = 11_000L
                    }
                }.body<ArrayList<Playlist.Song>>()
                viewModelScope.launch {
                    dataStore.edit { preferences ->
                        preferences[SAVED_STATE_PLAYLIST] = JSON.encodeToString(playlist)
                    }
                }
                playlist.reverse()
                Playlist(playlist)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to fetch playlist ${e.message}", Toast.LENGTH_LONG)
                dataStore
                    .data
                    .map { preferences -> preferences[SAVED_STATE_PLAYLIST] }
                    .first()
                    ?.let { Playlist(JSON.decodeFromString<List<Playlist.Song>>(it)) }
                    ?: throw e
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY] as Application)
                PlaylistViewModel(app)
            }
        }
    }
}

data class Playlist(val songs: List<Song>) {
    fun findByName(query: String): Song? = songs.find { it.name == query }
    fun findById(id: BangerId): Song? = songs.find { it.id == id }

    val categories by lazy {
        val map = HashMap<String, MutableList<Song>>()
        for (s in songs) {
            for (c in s.allCategories()) {
                map.getOrPut(c) { mutableListOf() }.add(s)
            }
        }
        map.asIterable().map { it.key to it.value }.sortedBy { -it.second.size }
    }

    @Serializable
    data class Song(
        val name: String,

        @SerialName("link")
        val id: BangerId,

        val time: Long,
        val categories: List<String>,
        val artist: String? = null,
        val genres: List<String> = listOf(),
        val language: String? = null,

        @SerialName("liked_by")
        val likedBy: List<String> = listOf(),

        @SerialName("recommended_by")
        val recommendedBy: String? = null,
    ) {
        fun allCategories() = categories.asSequence()
            .plusElement(artist)
            .plus(genres)
            .plusElement(language)
            .plus(likedBy)
            .plusElement(recommendedBy)
            .filterNotNull()
    }
}

@JvmInline
@Serializable(with = BangerIdSerializer::class)
value class BangerId(private val id: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString()!!)

    fun get(): String = id

    fun toAudioUri(): Uri =
        "${BuildConfig.MUSIC_BACKEND}/playlist/song/audio/${id}".toUri()

    fun toThumbnailUri(): Uri =
        "${BuildConfig.MUSIC_BACKEND}/playlist/song/thumb/${id}".toUri()

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BangerId> {
        fun fromUrl(s: String): BangerId {
            return BangerId(URL(s).path!!.split('/').last())
        }

        override fun createFromParcel(parcel: Parcel): BangerId {
            return BangerId(parcel)
        }

        override fun newArray(size: Int): Array<BangerId?> {
            return arrayOfNulls(size)
        }
    }
}

object BangerIdSerializer : KSerializer<BangerId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BangerId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BangerId) {
        encoder.encodeString("${BuildConfig.MUSIC_BACKEND}/playlist/song/audio/${value.get()}")
    }

    override fun deserialize(decoder: Decoder): BangerId {
        return BangerId.fromUrl(decoder.decodeString())
    }
}

@JvmInline
@Serializable(with = VideoIdSerializer::class)
value class VideoId(private val id: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString()!!)

    fun get(): String = id

    fun toAudioUri(): Uri =
        "${BuildConfig.OLD_MUSIC_BACKEND}/api/v1/playlist/audio/${id}".toUri()

    fun toThumbnailUri(): Uri =
        "${BuildConfig.OLD_MUSIC_BACKEND}/api/v1/playlist/thumb/${id}".toUri()

    fun toMetadataUri(): Uri =
        "${BuildConfig.OLD_MUSIC_BACKEND}/api/v1/playlist/metadata/${id}".toUri()

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<VideoId> {
        fun fromUrl(s: String): VideoId {
            return VideoId(URL(s).path!!.trimStart('/'))
        }

        override fun createFromParcel(parcel: Parcel): VideoId {
            return VideoId(parcel)
        }

        override fun newArray(size: Int): Array<VideoId?> {
            return arrayOfNulls(size)
        }
    }
}

object VideoIdSerializer : KSerializer<VideoId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VideoId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: VideoId) {
        encoder.encodeString("https://youtu.be/${value.get()}")
    }

    override fun deserialize(decoder: Decoder): VideoId {
        return VideoId.fromUrl(decoder.decodeString())
    }
}
