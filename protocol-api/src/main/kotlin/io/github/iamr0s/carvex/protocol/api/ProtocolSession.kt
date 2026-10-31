package io.github.iamr0s.carvex.protocol.api

/**
 * 协议会话契约（纯 JVM）。
 *
 * 方向：协议实现 → 本契约 → `:bridge`。
 * `:bridge` 负责把宿主（画面、音频、触摸）与协议会话接起来；协议实现不认识宿主。
 */
interface ProtocolRunner {
    val stack: ProtocolStack

    /** 建立会话；链路由调用方准备，协议只负责链路之上的交互。 */
    suspend fun open(context: SessionContext): ProtocolSession
}

/** 协议会话依赖的上下文；全部由编排层提供。 */
class SessionContext(
    /** 已建立的链路（蓝牙 RFCOMM / Wi-Fi / USB）。 */
    val transport: LinkTransport,
    /** 协议向编排层回报进度、错误、输入与音频需求的出口。 */
    val hooks: SessionHooks,
)

/** 协议 → 编排层的事件出口（协议不直接接触宿主）。 */
interface SessionHooks {
    fun log(message: String)

    /** 握手/会话失败；编排层决定是否重试或降级。 */
    fun failed(cause: Throwable)

    /** 握手进度；`detail` 允许为空，仅用于状态展示。 */
    fun progress(stage: HandshakeStage, detail: String)

    /** 归一化坐标（0..1）的触摸事件。 */
    fun touch(x: Float, y: Float, pressed: Boolean)

    /** 协议侧按键（prefix + usage 的语义由协议定义，编排层负责转成宿主键码）。 */
    fun key(prefix: String, usage: Int, down: Boolean)

    /** 车机请求打开某个界面（URL/动作）。 */
    fun ui(url: String)

    /** 车机昼夜模式切换。 */
    fun nightMode(mode: Int)

    /** 车机请求资源焦点。 */
    fun focus()

    /** 语音按键动作（Siri 等）。 */
    fun voice(action: String)

    /** 其它需要展示给用户/诊断的文本。 */
    fun message(text: String)

    /** 请求编排层开始某一路音频（宿主 → 协议方向的发声）。 */
    fun audioRequest(request: AudioRequest)

    /** 请求编排层停止某一路音频。 */
    fun audioStop(category: Int)

    /** 协议把车机麦克风采集的 PCM 交给编排层。 */
    fun microphone(pcm: ByteArray, rate: Int, channels: Int, encoding: Int)

    /** 保活状态回报；供编排层展示与诊断，不参与协议决策。 */
    fun keepAlive(status: KeepAliveStatus)

    /** 视频流协商结果（协议 → 编排）；编排据此创建编码器并配置几何。 */
    fun videoConfigured(width: Int, height: Int, fps: Int, codec: String, extra: ByteArray)

    /** 协议请求一个可写的音频通道（编排层返回实现，协议写入要发送的音频）。 */
    fun openAudio(request: AudioRequest): AudioChannel?

    /** 屏幕归属变化：true 表示画面由协议端（手机）提供，false 表示车机自己绘制。 */
    fun displayOwnership(phoneHasScreen: Boolean)
}

/** 由编排层提供的可写音频通道；协议通过它发送音频负载。 */
interface AudioChannel {
    /** 写入一帧音频；返回是否被接受（队列满/已关闭时返回 false）。 */
    fun write(payload: ByteArray, timestampUs: Long): Boolean

    /** 关闭通道；调用后编排层释放对应宿主音频。 */
    fun close()
}

/** 握手阶段（跨协议通用，具体细节由各协议在 detail 中补充）。 */
enum class HandshakeStage {
    CONNECTING,
    IDENTIFYING,
    AUTHENTICATING,
    WIRELESS_HANDOFF,
    DISCOVERY,
    SETUP,
    STREAMING,
    TEARDOWN,
}

/** 一路音频的请求参数。 */
data class AudioRequest(val category: Int, val rate: Int, val channels: Int, val encoding: Int)

/** 保活状态。 */
enum class KeepAliveStatus {
    /** 标准保活正常。 */
    OK,

    /** 保活超时（车机侧可能已断开）。 */
    TIMEOUT,

    /** 低功耗保活通道已启用。 */
    LOW_POWER_ACTIVE,
}

/** 一次协议会话；生命周期由编排层持有。 */
interface ProtocolSession : AutoCloseable {
    /** 编码后的视频帧入口；协议实现负责线缆格式（CarPlay 的 128 字节记录头等）。 */
    val video: VideoSink?

    /** 发送到车机的音频入口。 */
    val audio: AudioSink?

    suspend fun start()

    suspend fun stop(reason: StopReason)

    /** 请求调整码率；返回协议是否受理。 */
    fun requestBitrate(bps: Int): Boolean

    /** 请求调整帧率；返回协议是否受理。 */
    fun requestFrameRate(fps: Int): Boolean

    /** 请求立即产生关键帧。 */
    fun requestKeyFrame(): Boolean
}

/** 编码帧写入端；返回是否被接受。 */
fun interface VideoSink {
    fun frame(encoded: ByteArray, keyframe: Boolean, presentationUs: Long, config: Boolean): Boolean
}

/** 音频包写入端。 */
fun interface AudioSink {
    fun packet(payload: ByteArray, timestampUs: Long): Boolean
}

/** 已建立的链路（蓝牙 RFCOMM / Wi-Fi 连接 / USB 端点）；由编排层提供。 */
interface LinkTransport {
    val input: java.io.InputStream
    val output: java.io.OutputStream
    val description: String
    fun close()
}

/** 停止原因。 */
enum class StopReason { USER, REMOTE, FAILURE, TIMEOUT }
