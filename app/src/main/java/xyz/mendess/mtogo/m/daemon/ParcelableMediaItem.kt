package xyz.mendess.mtogo.m.daemon

import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import xyz.mendess.mtogo.viewmodels.VideoId

inline fun <reified T> Parcel.readObj(): T =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        readParcelable(T::class.java.classLoader, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        readParcelable(T::class.java.classLoader)
    }!!

sealed interface ParcelableMediaItem : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) =
        parcel.writeString(this::class.simpleName)

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ParcelableMediaItem> {
        override fun createFromParcel(parcel: Parcel): ParcelableMediaItem {
            return when (val name = parcel.readString()) {
                PlaylistItem::class.simpleName -> PlaylistItem.createFromParcel(parcel)
                Url::class.simpleName -> Url.createFromParcel(parcel)
                else -> throw IllegalArgumentException("'$name' is not a valid ${this::class.simpleName} name")
            }
        }

        override fun newArray(size: Int): Array<PlaylistItem?> = arrayOfNulls(size)

    }

    data class PlaylistItem(val id: VideoId) : ParcelableMediaItem {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeParcelable(id, flags)
        }

        override fun describeContents(): Int = 0

        companion object CREATOR : Parcelable.Creator<PlaylistItem> {
            override fun createFromParcel(parcel: Parcel) = PlaylistItem(parcel.readObj())
            override fun newArray(size: Int): Array<PlaylistItem?> = arrayOfNulls(size)
        }
    }

    data class Url(val uri: Uri) : ParcelableMediaItem {
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeParcelable(uri, flags)
        }

        override fun describeContents(): Int = 0

        companion object CREATOR : Parcelable.Creator<Url> {
            override fun createFromParcel(parcel: Parcel) = Url(parcel.readObj())

            override fun newArray(size: Int): Array<Url?> = arrayOfNulls(size)
        }
    }
}