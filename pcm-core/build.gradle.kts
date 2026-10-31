// :pcm-core —— 纯 JVM 的 PCM 契约与小工具
//
// PcmFormat / Pcm16Converter 既被协议栈使用（协商出来的音频参数），也被宿主模块使用
// （麦克风回传前的重采样）。放在这里，宿主模块就不必依赖整个 :protocol-carplay，
// 而 :protocol-carplay 也保持"纯 JVM、无 Android"。
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
