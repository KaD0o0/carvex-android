package com.baidu.carlife.mixing

import android.os.Parcel
import android.os.Parcelable
import android.view.Surface

class CastConfig(val width: Int, val height: Int, val dpi: Int,
                 val surface: Surface?) : Parcelable {
    private constructor(p: Parcel) : this(p.readInt(), p.readInt(), p.readInt(),
        p.readParcelable<Surface>(Surface::class.java.classLoader))
    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeInt(width); p.writeInt(height); p.writeInt(dpi)
        p.writeParcelable(surface, flags)
    }
    override fun describeContents() = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<CastConfig> {
            override fun createFromParcel(p: Parcel) = CastConfig(p)
            override fun newArray(size: Int): Array<CastConfig?> = arrayOfNulls(size)
        }
    }
}

class MusicInfo(val source: String?, val song: String?, val artist: String?,
                val duration: Long, val playlistSize: Int, val index: Int) : Parcelable {
    private constructor(p: Parcel) : this(p.readString(), p.readString(), p.readString(),
        p.readLong(), p.readInt(), p.readInt())
    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeString(source); p.writeString(song); p.writeString(artist)
        p.writeLong(duration); p.writeInt(playlistSize); p.writeInt(index)
    }
    override fun describeContents() = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<MusicInfo> {
            override fun createFromParcel(p: Parcel) = MusicInfo(p)
            override fun newArray(size: Int): Array<MusicInfo?> = arrayOfNulls(size)
        }
    }
}
