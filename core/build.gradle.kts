// :core —— 共享状态与工具（协议无关、生态无关）
//
// 放 BridgeStore/会话状态、完整性与 hook 检测、音频桥、握手通用工具等：
// 它们同时被 :bridge（编排）、:protocol-carplay（CarPlay 会话）和 :app（UI 读状态）使用，
// 因此必须位于"两者都依赖"的位置，而不是塞进协议模块或编排模块。
//
// 依赖方向：:core → :host-api / :protocol-api / :platform-android / :pcm-core
//            :bridge → :core、:protocol-carplay → :core
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.github.iamr0s.carvex.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
    api(project(":host-api"))
    api(project(":protocol-api"))
    api(project(":pcm-core"))
    api(project(":platform-android"))
    api(libs.coroutines.android)
}
