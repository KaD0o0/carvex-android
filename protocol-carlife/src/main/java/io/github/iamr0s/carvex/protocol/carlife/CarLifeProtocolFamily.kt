package io.github.iamr0s.carvex.protocol.carlife

/**
 * CarLife 协议族（预留）。
 *
 * 这里最终应放：通道建立后的消息编解码、帧格式、能力表与状态机——全部是纯 Kotlin，
 * 可像 `:protocol-carplay-core` 一样在 JVM 上跑单元测试。
 *
 * 现状：**没有实现**。模块存在只为锁定依赖方向：
 *   `:protocol-carlife`（Android 适配） → `:protocol-carlife-core`（纯 JVM）
 * 这样将来接 CarLife 时，不会被临时塞进 `:protocol-carplay-core` 或 `:bridge`。
 */
object CarLifeProtocolFamily {
    /** 协议族标识；与 `ProtocolStack.id` 无关，后者是运行期实现的名字。 */
    const val FAMILY: String = "carlife"
}
