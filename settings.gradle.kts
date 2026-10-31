pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "CarVeX"
// 分层（依赖严格单向向下）：
//
//   :protocol-carplay 内含 carplay.core（原语）与 carplay（Android 会话）
//   :pcm-core（纯 JVM）             → :host-api / :protocol-carplay
//   :platform-android（平台能力）    → :core / :protocol-carplay / :bridge
//   :host-api（宿主契约）            → :host-carlife-mixing
//   :protocol-api（协议契约，纯 JVM） → :protocol-carplay / :protocol-carlife
//   :core（共享状态与工具）          → :bridge / :protocol-carplay
//   :bridge（编排）                  → :app
//
// :bridge **不依赖任何宿主模块**：宿主由 :app 通过构造参数注入（见 SessionController）。
// :protocol-carlife 目前是预留骨架。
include(":app", ":host-carlife-mixing", ":host-api",
    ":pcm-core", ":platform-android", ":protocol-api",
    ":protocol-carplay", ":protocol-carlife", ":core", ":bridge")
