package io.github.iamr0s.carvex.host.vendor

import android.content.Context
import io.github.iamr0s.carvex.hostregistry.HostPlugin
import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks

private fun installed(context: Context, packageName: String) = runCatching {
    context.packageManager.getPackageInfo(packageName, 0) != null
}.getOrDefault(false)

private fun plugin(config: VendorHostConfig) = object : HostPlugin {
    override val id = config.id
    override val displayName = config.displayName
    override fun isAvailable(context: Context) = installed(context, config.hostPackage)
    override fun create(context: Context, callbacks: HostCallbacks): CastHost = VendorCastHost(context, callbacks, config)
}

/** Xiaomi CarWith endpoint. It uses the same mixing manager as the other vendors. */
val XiaomiHostPlugin: HostPlugin = plugin(VendorHostConfig(
    id = "xiaomi", displayName = "小米 CarWith",
    hostPackage = "com.miui.carlink",
    serviceClass = "com.xiaomi.ucar.carlife.CarlifeConnService",
))

/** OPPO Car+ endpoint. */
val OppoMixingHostPlugin: HostPlugin = plugin(VendorHostConfig(
    id = "oppo", displayName = "OPPO 车机互联",
    hostPackage = "com.oplus.ocar",
    serviceClass = "com.oplus.ocar.connect.carlife.CarlifeCastManagerService",
    serviceAction = "com.oplus.ocar.action.START_CARLIFE_SERVICE",
))

/** vivo endpoint. */
val VivoMixingHostPlugin: HostPlugin = plugin(VendorHostConfig(
    id = "vivo", displayName = "vivo 车机互联",
    hostPackage = "com.vivo.car.networking",
    serviceClass = "com.vivo.vivo_car_service.CarLifeMixingService",
))

val SamsungHostPlugin: HostPlugin = plugin(VendorHostConfig(
    id = "samsung", displayName = "三星车联",
    hostPackage = "com.samsung.android.carlink",
    serviceClass = "com.samsung.android.carlink.mixing.CarLifeMixingService",
    fallbackServiceClasses = listOf("com.samsung.android.carlink.carlife2.CarLifeMixingService"),
))

/**
 * 荣耀 APK 目前不能放进这个 mixing 适配器：10.0.0.373 的 CarUiService
 * 要求 com.hihonor.auto.HONOR_AUTO_PERMISSION_SYSTEM（signatureOrSystem），
 * 且没有证据表明它实现 ICarLifeCastManager。保留 flavor/插件标识用于打包和
 * 后续接入，但绝不把“应用已安装”误判成第三方可绑定。
 */
object HonorHostPlugin: HostPlugin {
    override val id: String = "honor"
    override val displayName: String = "荣耀车联（待系统接口）"
    override fun isAvailable(context: Context): Boolean = false
    override fun create(context: Context, callbacks: HostCallbacks): CastHost = UnavailableHost(displayName)
}
