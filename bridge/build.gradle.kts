// :bridge —— 编排层（Android）
//
// 只做"选协议、连宿主、重试降级、生命周期"：具体协议会话在 :protocol-carplay，
// 共享状态与工具在 :core，宿主实现只在 :host-*。
//
// 依赖方向：
//   :bridge → :core + :protocol-carplay + :host-api + :protocol-api + :platform-android
//   宿主模块（:host-carlife-mixing）**不依赖本模块**，只依赖 :host-api。
//
// 包结构：io.github.iamr0s.carvex.session（SessionController / 编码器 / 按键适配 / CastSession）
plugins { alias(libs.plugins.android.library) }

android {
    // namespace 只用于 R/Manifest，不能与 :app 的 io.github.iamr0s.carvex 冲突。
    namespace = "io.github.iamr0s.carvex.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
    api(project(":core"))
    // 当前唯一协议实现；将来 CarLife 也是同样方式接入。
    api(project(":protocol-carplay"))
    api(project(":host-api"))
    api(project(":protocol-api"))
    api(project(":platform-android"))
    api(libs.coroutines.android)
}
