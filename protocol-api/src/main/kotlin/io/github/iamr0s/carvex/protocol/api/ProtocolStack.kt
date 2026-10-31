package io.github.iamr0s.carvex.protocol.api

/**
 * 协议栈描述：编排层通过它了解"这个协议能做什么"。
 *
 * 与 `:host-api` 的 `CastHost` 无关：协议不需要知道宿主是谁，它只产出/消费数据。
 */
interface ProtocolStack {
    /** 稳定标识，用于设置项与日志；不要随版本变化。 */
    val id: String

    /** 界面展示名。 */
    val displayName: String

    /** 该协议栈自身的能力。 */
    val capabilities: ProtocolCapabilities
}

/** 协议栈能力声明；默认值即"全能力"，未声明的实现不需要写全字段。 */
data class ProtocolCapabilities(
    val video: Boolean = true,
    val audio: Boolean = true,
    val microphone: Boolean = true,
    val touch: Boolean = true,
    val keys: Boolean = true,
    /** 是否需要无线交接（蓝牙发现 + Wi-Fi 加入）才能建立会话。 */
    val wirelessHandoff: Boolean = true,
)
