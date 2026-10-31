package io.github.iamr0s.carvex.session

import io.github.iamr0s.carvex.sessioncore.HostKeyMapper

/**
 * 协议 → 宿主键码的翻译层。
 *
 * 这里**不再内置任何宿主码表**：媒体 usage 与 UI 事件前缀只翻译成语义，实际键码由
 * `HostKeyMapper`（宿主模块提供）给出。CarWith 的 8101…8115 等码值因此从 `:session` 移出。
 *
 * 表本身仍然没有持久状态、没有用户绑定查找：只有两端都能证明含义的 usage 才映射，
 * 未知 usage 返回 null，由调用方按"不支持"记录。
 */
class CarPlayKeyAdapter(private val mapper:HostKeyMapper?) {
    fun mediaUsage(usage:Int):Int? = mapper?.mediaKey(usage)

    /** Page-qualified HID mapping for Consumer, Telephony and future HID pages. */
    fun hidEvent(prefix:String,usage:Int):Int? = mapper?.hidKey(prefix,usage)

    /** No independent host toggle code is known; use Android's real media-session meaning. */
    fun systemMediaUsage(usage:Int):Int?=when(usage){
        0xcd->android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else->null
    }

    fun uiEvent(eventId:String):Int? = mapper?.uiKey(eventId)
}
