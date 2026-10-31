package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.protocol.api.ProtocolCapabilities
import io.github.iamr0s.carvex.protocol.api.ProtocolStack

/**
 * CarPlay 协议栈（iAP2 无线交接 + AirPlay 会话）。
 *
 * 这是当前唯一的协议实现；`:bridge` 只通过 `ProtocolStack` 读取它的标识与能力。
 * 将来加第二条协议栈时，新建一个实现并在 `:app` 注册，不需要改宿主模块。
 */
object CarPlayProtocol: ProtocolStack {
    override val id: String = "carplay"
    override val displayName: String = "CarPlay"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        video = true,
        audio = true,
        microphone = true,
        touch = true,
        keys = true,
        wirelessHandoff = true,
    )
}
