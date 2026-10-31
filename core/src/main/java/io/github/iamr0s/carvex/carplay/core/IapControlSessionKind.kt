package io.github.iamr0s.carvex.carplay.core

/** Control object role, independent of the transport, linkVersion and discovery direction.
 *  放在 :core 是因为连接模型（ConnectionMode）与协议状态机都要用它；它不含任何逻辑。 */
enum class IapControlSessionKind { CARPLAY, IPOD_BOOTSTRAP, OTHER }
