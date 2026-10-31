package io.github.iamr0s.carvex.host.vendor

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import io.github.iamr0s.carvex.sessioncore.CastHost

/** Keeps a flavor buildable when the vendor has no public third-party host contract yet. */
class UnavailableHost(private val name: String) : CastHost {
    override suspend fun bind() = error("$name host has no verified public mixing endpoint")
    override suspend fun selectCarLifePlusAudio() = Unit
    override suspend fun microphone(pcm: ByteArray, rate: Int, channels: Int, encoding: Int) = false
    override suspend fun surface(surface: Surface, width: Int, height: Int, dpi: Int) = Unit
    override suspend fun format(width: Int, height: Int): Bundle? = null
    override suspend fun invoke(action: String, configure: (android.content.Intent) -> android.content.Intent): Bundle? = null
    override fun key(event: KeyEvent) = false
    override fun touch(event: MotionEvent) = false
    override fun close() = Unit
}
