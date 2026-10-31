// :host-carlife-mixing —— 所有已验证 CarLife mixing 宿主的统一适配模块
//
// 小米 CarWith、OPPO Car+、vivo 智能车载和三星车联都暴露
// com.baidu.carlife.mixing.aidl.ICarLifeCastManager。品牌差异只保留在
// service component、包名、启动参数和可选宿主键码映射中，AIDL 与 PCM/回调适配只维护一份。
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.iamr0s.carvex.host.mixing"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    api(project(":host-api"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
