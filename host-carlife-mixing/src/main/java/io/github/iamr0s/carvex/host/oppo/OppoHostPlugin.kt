package io.github.iamr0s.carvex.host.oppo

import io.github.iamr0s.carvex.hostregistry.HostPlugin
import io.github.iamr0s.carvex.host.vendor.OppoMixingHostPlugin

/** OPPO Car+ 的 CarLife mixing 服务配置。AIDL descriptor 已由宿主 APK 验证。 */
object OppoHostPlugin: HostPlugin by OppoMixingHostPlugin
