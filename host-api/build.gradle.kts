// :host-api —— 宿主与协议之间的唯一契约层
//
// 只放"两边都同意的东西"：HostBridge / ProtocolStack 相关接口、能力声明、宿主插件注册表。
// 不含实现、不含 iAP2/AirPlay 的任何类型，因此 :host-* 模块编译时看不到协议库。
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.iamr0s.carvex.hostapi"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    // 纯 JVM 的 PCM 契约（PcmFormat / Pcm16Converter）：宿主实现直接用它，不必依赖协议库。
    api(project(":pcm-core"))
    // ProtocolSession.events 用 Flow；协议与宿主两侧都只看到接口。
    api(libs.coroutines.android)
    testImplementation(libs.junit)
}
