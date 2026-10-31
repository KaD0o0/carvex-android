package io.github.iamr0s.carvex.carplay.core

/**
 * 数字音频通路（feature 9）与目的地共享（feature 23）的**订阅/状态解析**。
 *
 * 这两组消息本身不含音频或导航数据：`da00` 只是打开数字音频通路，`da02` 关闭；
 * `10da` 开始目的地信息、`10dc` 停止、`10dd` 是车机回给手机的状态。
 * 本实现没有平台数据源，因此只解析并记录，不产生 `da01`/`10db` 更新。
 */
object DigitalAudioAndDestination {

    // ---- feature 9：数字音频 ----

    @Volatile var digitalAudioActive: Boolean = false; private set

    /** `da00`：无参数；收到即置位。 */
    fun startDigitalAudio() {
        digitalAudioActive = true
    }

    /** `da02`：无参数；收到即清零。 */
    fun stopDigitalAudio() {
        digitalAudioActive = false
    }

    /** `da01`：参数 0 SampleRate(U8) 必需；1 VolumeLevel(U32)、2 SoundCheck(U32) 可选（ch17 §2.16）。 */
    fun digitalAudioInfo(sampleRate: Int, volumeLevel: Int? = null, soundCheck: Int? = null): List<Pair<Int, ByteArray>> {
        require(sampleRate in 0..255) { "SampleRate is a U8" }
        val out = mutableListOf<Pair<Int, ByteArray>>(0 to byteArrayOf(sampleRate.toByte()))
        volumeLevel?.let { out += 1 to IapWire.be32(it.toLong() and 0xffffffffL) }
        soundCheck?.let { out += 2 to IapWire.be32(it.toLong() and 0xffffffffL) }
        return out
    }

    /** 平台 capability 集（iAP2 feature id）；没有设置时 `10da/10dc` 按“feature 不存在”拒绝。 */
    @Volatile var availableFeatures: Set<Int> = emptySet()
        private set

    fun setAvailableFeatures(features: Set<Int>) {
        availableFeatures = features.toSet()
    }

    private fun hasDestinationFeature() = 0x17 in availableFeatures

    private fun i32(value: Long) = IapWire.be32(value and 0xffffffffL)

    /** 坐标是 i32 定点 Q10.22（度 × 2²²）；|lat|>90 或 |lon|>180 丢弃（ch16 §2.10）。 */
    private fun coordinate(lat: Double, lon: Double): ByteArray? {
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        val latQ = Math.round(lat * 4194304.0)
        val lonQ = Math.round(lon * 4194304.0)
        return IapWire.fields(listOf(0 to i32(latQ), 1 to i32(lonQ)))
    }

    /** `10db` 目的地更新编码器（ch16 §2.10）；没有导航数据源时不会被调用。 */
    fun destinationUpdate(
        displayName: String? = null,
        sourceName: String? = null,
        locale: String? = null,
        address: String? = null,
        center: Pair<Double, Double>? = null,
        entryPoints: List<Pair<Double, Double>> = emptyList(),
        coordinateThreshold: Long? = null,
        localizedName: String? = null,
    ): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        displayName?.let { out += 0 to IapWire.cstring(it) }
        sourceName?.let { out += 1 to IapWire.cstring(it) }
        address?.let { out += 3 to IapWire.cstring(it) }
        locale?.let { out += 6 to IapWire.cstring(it) }
        center?.let { (lat, lon) -> coordinate(lat, lon)?.let { out += 2 to it } }
        val points = entryPoints.mapNotNull { (lat, lon) -> coordinate(lat, lon) }
        if (points.isNotEmpty()) out += 4 to IapWire.fields(points.mapIndexed { _, block -> 0 to block })
        coordinateThreshold?.let { out += 5 to i32(it) }
        // 参数 7 只在目的地信息已激活(+2=1)时发送。
        if (destinationActive) localizedName?.let { out += 7 to IapWire.cstring(it) }
        return out
    }

    // ---- feature 23：目的地共享 ----

    /** `10da` 的解析结果；null 表示按固件应整条拒绝。 */
    @Volatile var destinationActive: Boolean = false; private set
    /** `10dd` 状态：是否失败、以及状态串（参数 0，后到覆盖）。 */
    @Volatile var destinationFailed: Boolean = false; private set
    @Volatile var destinationStatus: String? = null; private set
    @Volatile var destinationReasonKeys: Set<Int> = emptySet(); private set

    /**
     * `10da`：参数 id 只允许 0，且必须空载荷、至多一次；非空或重复 → 拒绝。
     * 返回 false 表示整条拒绝。
     */
    fun startDestination(fields: IapFields): Boolean {
        if (!hasDestinationFeature()) return false
        var seen = false
        for ((id, payload) in fields.ordered) {
            if (id != 0) continue
            if (seen || payload.isNotEmpty()) return false
            seen = true
        }
        destinationActive = true
        return true
    }

    /** `10dc`：无定义参数，任何参数只记日志，不拒。 */
    fun stopDestination(): Boolean {
        if (!hasDestinationFeature()) return false
        destinationActive = false
        return true
    }

    /**
     * `10dd`：参数 0 = 字符串（失败则拒），参数 1 = void（出现即"失败"），参数 2 = 组
     * （子 id 2..4 必须 void）。返回 false 表示整条拒绝。
     */
    fun destinationStatusUpdate(fields: IapFields): Boolean {
        var text: String? = null
        var failed = false
        val keys = mutableSetOf<Int>()
        for ((id, payload) in fields.ordered) {
            when (id) {
                0 -> {
                    if (payload.isEmpty()) return false
                    text = runCatching { payload.toString(Charsets.UTF_8).trimEnd('\u0000') }.getOrNull() ?: return false
                }
                1 -> failed = true
                2 -> {
                    val children = runCatching { IapWire.parseFieldList(payload) }.getOrNull() ?: return false
                    for ((childId, childPayload) in children.ordered) {
                        if (childId !in 2..4) continue
                        if (childPayload.isNotEmpty()) return false
                        keys += childId
                    }
                }
                else -> Unit
            }
        }
        if (text != null) destinationStatus = text
        destinationFailed = failed
        destinationReasonKeys = keys
        return true
    }
}
