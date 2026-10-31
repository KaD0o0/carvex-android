// :protocol-carlife —— CarLife 协议适配（预留骨架，Android）
//
// 实现 `ProtocolRunner`（见 :host-api），把 CarLife 会话接到统一的编排接口上。
// 它可以使用 :platform-android 的蓝牙/Wi-Fi/网络能力，但不依赖 :bridge，
// 也不认识任何宿主应用 —— 宿主由 :bridge 通过 `CastHost` 注入。
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.iamr0s.carvex.carlife"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
    api(project(":protocol-api"))
    api(project(":platform-android"))
    implementation(libs.coroutines.android)
}
