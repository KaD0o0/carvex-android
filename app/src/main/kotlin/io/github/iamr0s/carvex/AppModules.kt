package io.github.iamr0s.carvex

import io.github.iamr0s.carvex.carplay.CarPlayProtocol
import io.github.iamr0s.carvex.host.vendor.XiaomiHostPlugin
import io.github.iamr0s.carvex.host.vendor.OppoMixingHostPlugin
import io.github.iamr0s.carvex.host.vendor.VivoMixingHostPlugin
import io.github.iamr0s.carvex.host.vendor.SamsungHostPlugin
import io.github.iamr0s.carvex.host.vendor.HonorHostPlugin
import io.github.iamr0s.carvex.hostregistry.HostPlugin
import io.github.iamr0s.carvex.protocol.api.ProtocolStack
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.core.qualifier.named

/**
 * 组装层：Koin 只负责"把实现塞进接口"，不接管会话生命周期。
 *
 * 规则：
 *  1. 只有 `:app` 调用 `startKoin`；各库模块不依赖 Koin。
 *  2. 宿主插件按名字注册；`HostRegistry` 在启动时从 Koin 取出并安装。
 *  3. 一次连接的对象（SessionController 及其资源）仍由 Service 显式创建与关闭，
 *     不交给 Koin scope —— 避免会话生命周期与 DI 容器生命周期纠缠。
 */
val appModules: Module = module {
    // 协议栈：当前只有 CarPlay；接第二条时新增一行注册即可。
    single<ProtocolStack> { CarPlayProtocol }

    // 宿主插件：每个生态一个命名项；未安装的生态由 isAvailable 自行排除。
    single<HostPlugin>(named("xiaomi")) { XiaomiHostPlugin }
    single<HostPlugin>(named("oppo")) { OppoMixingHostPlugin }
    single<HostPlugin>(named("vivo")) { VivoMixingHostPlugin }
    single<HostPlugin>(named("samsung")) { SamsungHostPlugin }
    single<HostPlugin>(named("honor")) { HonorHostPlugin }
}
