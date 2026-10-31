package io.github.iamr0s.carvex.host.mixing.service

/** Build-time identity and host entry contract for one distributable CarLife package. */
data class MixingBrand(
    val id: String,
    val applicationId: String,
    val hostPackage: String,
    val hostServiceClasses: Set<String>,
    val manufacturer: String,
    val pluginId: String,
) {
    fun acceptsHostIntent(packageName: String?, className: String?, manufacturerValue: String?, usbType: Int, isBox: Boolean): Boolean =
        packageName == hostPackage && className in hostServiceClasses &&
            manufacturerValue == manufacturer && usbType in 0..1 && !isBox

    companion object {
        val all = listOf(
            MixingBrand("xiaomi", "com.baidu.carlife.xiaomi", "com.miui.carlink", setOf("com.xiaomi.ucar.carlife.CarlifeConnService"), "xiaomi", "xiaomi"),
            MixingBrand("oppo", "com.baidu.carlife.oppo", "com.oplus.ocar", setOf("com.oplus.ocar.connect.carlife.CarlifeCastManagerService"), "oppo", "oppo"),
            MixingBrand("vivo", "com.baidu.carlife.vivo", "com.vivo.car.networking", setOf("com.vivo.vivo_car_service.CarLifeMixingService"), "vivo", "vivo"),
            MixingBrand("samsung", "com.baidu.carlife.samsung", "com.samsung.android.carlink", setOf("com.samsung.android.carlink.mixing.CarLifeMixingService", "com.samsung.android.carlink.carlife2.CarLifeMixingService"), "samsung", "samsung"),
            // Honor 10.0.0.373 exposes CarUiService only behind HONOR_AUTO_PERMISSION_SYSTEM;
            // it is not a verified third-party CarLife mixing callback. Keep the flavor for
            // packaging, but accept no host entry until an exported AIDL contract is obtained.
            MixingBrand("honor", "com.baidu.carlife.honor", "com.hihonor.auto", emptySet(), "honor", "honor"),
        )

        /** The app supplies its flavor; this library never imports an application's BuildConfig. */
        fun forFlavor(flavor: String): MixingBrand =
            requireNotNull(all.firstOrNull { it.id == flavor }) { "Unknown CarLife mixing flavor: $flavor" }
    }
}
