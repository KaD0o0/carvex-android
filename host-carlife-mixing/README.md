# CarLife mixing 宿主

所有已接入的品牌共用 `VendorCastHost`，模块名称统一为 `:host-carlife-mixing`。

## 统一的实现

- 一份 `com.baidu.carlife.mixing.aidl`，包含 manager、callback、音频、按键和触摸 listener。
- 一份绑定、Binder descriptor/回调 UID 检查、Surface、`invokeMethod()`、媒体信息和生命周期逻辑。
- 一份麦克风 PCM 转换，按 listener binding 保持重采样相位，输出 16 kHz 单声道 PCM16。
- 一份语音麦克风和采样率查询处理，以及 PCM 默认音频路由策略。
- 一份 `MixingKeys` / `MixingKeyMapper`，用于四个已核对组件的语义键转换。

`selectCarLifePlusAudio()` 保留现有 PCM 路由，不主动发送蓝牙音频路由请求。
这是当前桥接实现的统一策略，不能解释成“小米 CarLife 组件禁止 audioType=2”。
后续若接入音频路由协商，应根据已协商的通道和宿主回复实现。

## 品牌配置

配置集中在 `src/main/java/io/github/iamr0s/carvex/host/vendor/VendorHostPlugins.kt`：

| 品牌 | 宿主包名 | Service | 附加配置 |
| --- | --- | --- | --- |
| 小米 | `com.miui.carlink` | `com.xiaomi.ucar.carlife.CarlifeConnService` | 无 |
| OPPO | `com.oplus.ocar` | `com.oplus.ocar.connect.carlife.CarlifeCastManagerService` | `com.oplus.ocar.action.START_CARLIFE_SERVICE` |
| vivo | `com.vivo.car.networking` | `com.vivo.vivo_car_service.CarLifeMixingService` | 无 |
| 三星 | `com.samsung.android.carlink` | `com.samsung.android.carlink.mixing.CarLifeMixingService` | 备用 `com.samsung.android.carlink.carlife2.CarLifeMixingService` |
| 荣耀 | `com.hihonor.auto` | 尚无已确认的公开 mixing endpoint | 保留打包变体，宿主插件明确不可用 |

`CarWithHost` 和旧品牌插件名称仅作为源码兼容门面，委托到同一个实现和配置。
小米插件 ID 已统一为 `xiaomi`。

四个品牌默认使用同一个 `MixingKeyMapper`，按键范围由
`CarWithKeysTest` 和 `MixingKeyMapperTest` 固定。8116 作为 Consumer Mute
的静音语义接入。品牌插件只选择宿主入口，不改变统一的按键语义。

## Service 隔离

`com.baidu.carlife.service.CarlifeConnectService` 属于本模块，包括 CarLife Intent 字段校验、
内部连接/断开令牌、KeepAlive 绑定、前台 Service 和会话生命周期。
其宿主可见的完整类名保持不变。Service 声明、宿主包可见性查询和前台服务权限
也由本模块的 `src/main/AndroidManifest.xml` 提供，经 Manifest merger 合入各品牌 APK。

入口规则位于 `host/mixing/service/MixingBrand.kt`。应用仅提供编译时 flavor，
未知 flavor 会报错，不自动选择小米；荣耀仍不接受未经确认的 Service 入口。

应用的 `Application` 实现 `MixingServiceOwner`，为每个 Service 实例创建
`MixingServiceDelegate`。接口提供：

- `MixingServiceSession`：启动、断开、释放、状态流和会话操作。
- 应用权限检查、通知构建和日志记录。

当前应用的接线在 `app/.../AppMixingServiceDelegate.kt`：负责创建 `SessionController`、
读取 `BridgeStore`、Shizuku 准备，以及 UI 通知资源。Service 通过接口消费这些能力，
本模块不依赖 `:app`、`:bridge`、`:core`、Koin、Compose 或具体 CarPlay 实现。
重复的连接请求在连接准备期间合并，断开时取消准备任务和状态订阅。

## 打包

应用的品牌由 `app` 的 product flavor 决定。各变体使用同一个宿主模块，并选择自己的插件，
不自动回退到其他品牌宿主。

| flavor | APK 包名 |
| --- | --- |
| `xiaomi` | `com.baidu.carlife.xiaomi` |
| `oppo` | `com.baidu.carlife.oppo` |
| `vivo` | `com.baidu.carlife.vivo` |
| `samsung` | `com.baidu.carlife.samsung` |
| `honor` | `com.baidu.carlife.honor` |

```powershell
.\gradlew.bat :host-carlife-mixing:test :app:assembleXiaomiDebug :app:assembleOppoDebug :app:assembleVivoDebug :app:assembleSamsungDebug :app:assembleHonorDebug --no-daemon
```

APK 产物：`app/build/outputs/apk/<brand>/<debug|release>/CarVex_<brand>_<debug|release>_<version>.apk`。
版本名是纯数字，与 versionCode 相同（20000＋Git 提交数）；例如 `CarVex_xiaomi_release_20001.apk`。
Android Studio 的 Generate Signed App Bundle or APK 使用相同命名规则；AAB 扩展名为 `.aab`。

2026-10-24 Service 隔离及共享按键映射后本地验证：宿主模块 14 项测试通过（含 5 项品牌入口校验），五个 Debug APK 构建成功，
并从 APK 的 `aapt dump badging` 核对了上述包名。尚未完成各品牌手机和车机联调。
荣耀构建成功仅说明可打包，不代表宿主已接入。
