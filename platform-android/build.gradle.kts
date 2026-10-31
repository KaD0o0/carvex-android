// :platform-android —— 与协议、与生态都无关的平台能力
//
// 只放"任何协议栈都可能用到"的 Android 能力：蓝牙设备查询、网络地址筛选等。
// 它**不认识** CarPlay 的消息号，也**不认识**任何宿主应用；
// 因此 :bridge 与将来的 :protocol-carlife / :protocol-hicar 都可以依赖它。
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.iamr0s.carvex.platform"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
}
