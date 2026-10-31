package io.github.iamr0s.carvex.sessioncore

import android.content.Intent

/**
 * 宿主播向编排层的事件与数据。
 *
 * 这些类型**不属于任何一个生态**：各品牌 CarLife mixing endpoint 在自己的模块里把 AIDL/Intent
 * 翻译成本接口，编排层只认这里。宿主特有的类型（例如 CarLife 的 `MusicInfo`）不得出现在
 * `:session` 里。
 */
data class HostMusic(
    val source: String?,
    val song: String?,
    val artist: String?,
    val duration: Long = 0,
    val playlistSize: Int = 0,
    val index: Int = 0,
)

/**
 * 宿主能力声明：编排层按这些能力决定"做不做"，而不是假设某个生态的能力。
 *
 * 未知字段保留默认值；`CastHost.capabilities()` 返回 null 表示宿主没有声明，
 * 编排层维持既有行为（等价于全能力 + 不限制 FPS）。
 */
data class HostCapabilities(
    /** 宿主是否接受触摸事件。 */
    val touch: Boolean = true,
    /** 宿主是否接受按键事件。 */
    val keys: Boolean = true,
    /** 宿主是否提供麦克风回传通道。 */
    val microphone: Boolean = true,
    /** 宿主能同时承载的画面数；1 表示只支持单屏。 */
    val displayCount: Int = 1,
    /** 宿主可接受的最大帧率；null 表示不限制。 */
    val maxFps: Int? = null,
)

/** 宿主播向编排层的回调；实现者在编排层，调用者在宿主模块。 */
interface HostCallbacks {
    fun event(name: String, value: Int = 0)
    fun pcm(data: ByteArray, category: Int, rate: Int, channels: Int, encoding: Int)
    fun fps(value: Int)
    fun audioState(category: Int, state: Int) {
        event("audio-$category", state)
    }

    /** 宿主当前曲目。字段是已归一化的语义，不携带任何生态专有结构。 */
    fun music(info: HostMusic?) {}

    fun musicProgress(progress: Long) {}
    fun optionalAction(intent: Intent) {}
    fun musicSampleRate(): Int? = null
    fun vehicleMicrophoneReady(): Boolean = false
}

/**
 * 宿主键码映射：协议侧只产出"语义键"，由宿主决定它自己的键码。
 *
 * 之前这张表写死在 `:session` 里（`CarPlayKeyAdapter` + `CarWithKeys`），等于把一个品牌的
 * 键码当成协议常量；接第二个生态时就会冲突。现在协议层只问语义，宿主给码。
 */
interface HostKeyMapper {
    /** 语音开始/结束的宿主键码（协议侧 `requestSiri` 等事件会用到）。 */
    fun voiceStart(): Int
    fun voiceStop(): Int

    /** CarPlay 媒体 usage → 宿主键码；不支持时返回 null。 */
    fun mediaKey(usage: Int): Int?

    /** CarPlay HID 键：保留 usage page 前缀，避免 Telephony 与 Consumer 同值碰撞。 */
    fun hidKey(prefix: String, usage: Int): Int? =
        if (prefix == "media") mediaKey(usage) else null

    /** CarPlay UI 事件前缀（`ui:maps:` 等）→ 宿主键码；不支持时返回 null。 */
    fun uiKey(eventId: String): Int?
}
