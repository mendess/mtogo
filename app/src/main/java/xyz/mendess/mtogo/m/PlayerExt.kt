package xyz.mendess.mtogo.m

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import xyz.mendess.mtogo.m.daemon.MediaItems

data class CurrentSong(
    val title: String,
    val categories: ArrayList<String>,
    val thumbNailUri: Uri?,
)

val Player.totalDurationMs: Long?
    get() = when (val duration = this.duration) {
        C.TIME_UNSET -> null
        else -> duration
    }

val Player.positionMs: Long
    get() = this.contentPosition


fun Player.nextSongs(count: UInt): List<String> {
    return (currentMediaItemIndex..<mediaItemCount)
        .asSequence()
        .drop(1)
        .take(count.toInt())
        .map { getMediaItemAt(it) }
        .map { it.mediaMetadata.title?.toString() ?: "No title" }
        .toList()
}

fun Player.prevSongs(count: UInt): List<String> {
    return (0..<currentMediaItemIndex)
        .reversed()
        .drop(1)
        .take(count.toInt())
        .map { getMediaItemAt(it) }
        .map { it.mediaMetadata.title?.toString() ?: "No title" }
        .toList()
}

fun Player.currentSong(): CurrentSong? {
    return if(mediaItemCount == 0) null
    else mediaMetadata.toCurrentSong()
}

fun MediaMetadata.toCurrentSong(): CurrentSong {
    return CurrentSong(
        title = title?.toString() ?: "unknown title",
        categories = extras
            ?.getStringArrayList(MediaItems.MUSIC_METADATA_CATEGORIES)
            ?: ArrayList(),
        thumbNailUri = extras
            ?.getString(MediaItems.MUSIC_METADATA_THUMBNAIL_ID)
            ?.let(Uri::parse)
    )
}