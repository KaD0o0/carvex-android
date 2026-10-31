package io.github.iamr0s.carvex.session
import io.github.iamr0s.carvex.sessioncore.*

import android.content.Context
import android.view.MotionEvent
import android.view.Surface

/** Page shown by the app-owned video source. */
enum class LocalVideoPhase { SPLASH, SCREEN }

/** App-owned content drawn into the encoder surface through a private VirtualDisplay.
 * The implementation lives in the UI module so this layer stays free of Compose and Android views. */
interface LocalVideoContent:AutoCloseable {
    /** Switches the visible page; safe to call repeatedly and ignored after close. */
    fun show(phase:LocalVideoPhase)
    /** Resubmits the current page while the encoder is quiet, so a still page cannot silence the
     *  media stream. Defaults to nothing, for content that cannot repeat itself. */
    fun heartbeat(){}
    /** 车机回传的一次触摸，坐标已经映射到本画面的坐标系。返回是否被消费；只画不点的内容不用
     *  实现它。回调不在主线程上，实现方自己负责切线程。 */
    fun touch(event:MotionEvent):Boolean=false
}

/** Opens private-display content for one encoder surface. Returns null when the device refuses it,
 * so the caller can skip local content instead of failing the session. */
fun interface LocalVideoContentFactory {
    suspend fun open(context:Context,surface:Surface,width:Int,height:Int,fps:Int):LocalVideoContent?
}
