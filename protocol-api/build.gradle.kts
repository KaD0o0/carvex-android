// :protocol-api —— 协议对接契约（纯 JVM）
//
// 只声明"协议实现必须提供什么"：会话入口、编码帧出口、音频出口、麦克风来源、触摸/按键产出、
// 事件流。**不出现任何 Android 类型**，也不引用宿主契约（CastHost 只在 :bridge 里出现）。
//
// 这样协议实现（:protocol-carplay / :protocol-carlife）可以像 :protocol-carplay 一样
// 在 JVM 上单测，而不必拉起 Android。
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
