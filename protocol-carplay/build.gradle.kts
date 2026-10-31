// :protocol-carplay —— CarPlay 协议实现（iAP2 / RTSP / AirPlay / RTP / Bonjour 会话）
//
// 与 :pcm-core（纯 JVM PCM 契约）的区别：本模块是 Android 适配层，
// 负责 Socket、NsdManager、Surface/MediaCodec、与 :core 的状态交互。
//
// 依赖方向：本模块 → :core / :protocol-api / :pcm-core / :platform-android
// **不得**反向依赖 :bridge（编排层）。
plugins { alias(libs.plugins.android.library) }

// Temporary, single-variable navigation/assistant RED isolation. Restore the
// previous history-bearing sender with -PcarplayPrimaryOnlyRed=false.
val primaryOnlyRed = providers.gradleProperty("carplayPrimaryOnlyRed").orElse("true").get().let { value ->
    require(value == "true" || value == "false") {
        "carplayPrimaryOnlyRed must be exactly true or false"
    }
    value
}

// Speech settings diagnostics have been retired. Keep this separate build-time
// RED history isolation unchanged until its removal is independently validated.

android {
    namespace = "io.github.iamr0s.carvex.carplay.core.carplay"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField("boolean", "CARPLAY_PRIMARY_ONLY_RED", primaryOnlyRed)
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
    api(project(":core"))
    api(project(":protocol-api"))
    api(project(":pcm-core"))
    api(project(":platform-android"))
    api(libs.coroutines.android)
    // 原语里的证书解析等随核心一起并入本模块。
    implementation(libs.bouncycastle)
}
