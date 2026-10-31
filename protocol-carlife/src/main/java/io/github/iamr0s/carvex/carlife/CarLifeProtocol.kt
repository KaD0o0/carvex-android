package io.github.iamr0s.carvex.carlife

import io.github.iamr0s.carvex.protocol.api.ProtocolCapabilities
import io.github.iamr0s.carvex.protocol.api.ProtocolStack

/**
 * CarLife 协议栈描述（预留）。
 *
 * 只是让 `:protocol-carlife` 有一个 `ProtocolStack` 入口；**没有注册到 `:app`**，
 * 也不会被选中。真正接入时需要：
 *  1. 在 `:protocol-carlife-core` 实现消息与状态机；
 *  2. 在本模块实现 `ProtocolRunner`（`open()` 返回 `ProtocolSession`）；
 *  3. 在 `:app` 的 `AppModules.kt` 注册一行。
 */
object CarLifeProtocol: ProtocolStack {
    override val id: String = "carlife"
    override val displayName: String = "CarLife"
    // 预留：CarLife 是否支持触摸/按键/麦克风以实际协商结果为准，接入时再按固件/文档修正。
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        video = true,
        audio = true,
        microphone = true,
        touch = true,
        keys = true,
        wirelessHandoff = true,
    )
}
