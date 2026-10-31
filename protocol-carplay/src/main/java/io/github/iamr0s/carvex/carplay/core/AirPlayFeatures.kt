package io.github.iamr0s.carvex.carplay.core
/** AirPlay `features` 掩码的逐位语义。
 *
 * 位号有两处车机固件观测与一份第三方规格生成体互证（gap-closure §2.1/§2.3、catplay-cross-reference §6）：
 * 老平台置位 7/8/9/11/18/22/26/30/32、T3 置位 7/9/18/26/32/37/38，与下表逐位吻合。
 *
 * 证据级别：位号本身【实证】（固件里就是这些常量 OR 在一起）；**名字**来自第三方规格生成体，
 * 属 B 级 —— 与固件互证一致，但不代表 Apple 官方文档。因此这里只用它决定"要不要试某条路"，
 * 不用它排除任何已观测到的行为。
 *
 * 一个位缺失（车机没置位）与"未知"（拿不到掩码）不同：见 [AirPlayCapabilities.state]。
 */
enum class AirPlayFeature(val bit:Int) {
    VIDEO_FAIRPLAY(2),
    SCREEN(7),
    ROTATE(8),
    AUDIO(9),
    REDUNDANT_AUDIO(11),
    FPSAP_V2PT5_AES_GCM(12),
    AUTHENTICATION4(14),
    AUDIO_METADATA_ARTWORK(15),
    AUDIO_METADATA_PROGRESS(16),
    AUDIO_METADATA_TEXT(17),
    AUDIO_PCM(18),
    AUDIO_ALAC(19),
    AUDIO_AAC_LC(20),
    AUDIO_AAC_ELD(21),
    AUDIO_UNENCRYPTED(22),
    AUDIO_AES_128_MFI_SAP_V1(26),
    PAIRING(27),
    UNIFIED_METADATA(29),
    UNIFIED_BONJOUR(30),
    CAR(32),
    CARPLAY_CONTROL(37),
    HK_PAIRING_AND_ENCRYPT(38),
    BUFFERED_AUDIO(40),
    SUPPORTS_1588_CLOCK(41),
    SUPPORTS_SCREEN_MULTI_CODEC(42),
    SYSTEM_PAIRING(43),
    SUPPORTS_HARDWARE_1588_CLOCK(45),
    SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL(46),
    SUPPORTS_HK_PEER_MANAGEMENT(47),
    TRANSIENT_PAIRING(48),
    SUPPORTS_SET_PEERS_EXTENDED_MESSAGE(52);

    val mask:ULong get()=1UL shl bit

    companion object {
        private val byBit=values().associateBy{it.bit}
        /** 只认已知位；未知位返回 null —— 不编造名字，也不丢弃（掩码原文仍留在 [AirPlayCapabilities.features]）。 */
        fun at(bit:Int):AirPlayFeature?=byBit[bit]
    }
}

/** 一个来源给出的 AirPlay 能力视图（Bonjour TXT 的 `features`，或 `GET /info` 的 `features`）。
 *
 * [features] 为 null 表示**没有拿到掩码**，这是常态而不是异常：老平台与 T3 的 TXT 都可能缺键，
 * `/info` 也可能没有该字段。因此查询返回三态：true / false / null（未知）。
 * 未知不等于不支持，也不授权认证降级；实际安全入口由 SecurityRouter 的显式门槛决定。
 */
data class AirPlayCapabilities(val features:ULong?) {
    fun state(feature:AirPlayFeature):Boolean?=features?.let{it and feature.mask!=0UL}

    /** "确定有"才是 true；未知与确定没有都返回 false。只用于"必须确定支持才做"的动作。 */
    fun has(feature:AirPlayFeature):Boolean=state(feature)==true

    /** 已置位的位名，按位号升序；未知位以 `bit<N>` 形式保留，便于日志里对齐固件观测。 */
    fun names():List<String> {
        val bits=features?:return emptyList()
        return (0..63).filter{bits and (1UL shl it)!=0UL}.map{AirPlayFeature.at(it)?.name?:"bit$it"}
    }

    companion object {
        val unknown=AirPlayCapabilities(null)
        /** 解析十六进制单段或 low,high 两段（可选0x前缀）；不猜测十进制。 */
        fun parse(value:String?):AirPlayCapabilities=AirPlayCapabilities(ReceiverIdentity.features(value))
    }
}

/** CarPlay 的 AirPlay 世代。文档里出现的两种形态：AirPlay 1（仅 `/auth-setup`）与 AirPlay 2（HAP）。 */
enum class AirPlayGeneration { UNKNOWN, V1, V2 }

/** 判定世代用到的运行时证据 —— 每个字段都可缺失，且**缺失不等于否**。
 *
 * 判据来自三个车机样本（t3-carplay-spec §9.2/§9.7/§9.8、carplay-client-spec §5.2/§6.1）：
 * - `protovers` 存在 ⇒ AirPlay 1；`/info` 的 `protocolVersion == "1.0"` 同理（老平台）
 * - 服务集出现 `_raop` / `_hap` ⇒ AirPlay 2（T3、GM）
 * - features 位 38 / 46（HK 配对与加密）⇒ AirPlay 2
 * - `/pair-verify` 实际可访问 ⇒ AirPlay 2；返回不可用 ⇒ AirPlay 1（能力探测，用于交叉验证）
 */
data class AirPlayGenerationEvidence(
    val protocolVersion:String?=null,
    val sourceVersion:String?=null,
    val features:AirPlayCapabilities=AirPlayCapabilities.unknown,
    val services:Set<String> = emptySet(),
    val pairVerifyAvailable:Boolean?=null
)

object AirPlayGenerations {
    private val v2OnlyServices=setOf("_raop._tcp.","_hap._tcp.")
    /** Cinemo（GM）的实现线索：仅记录，不当世代判据（diplay-cross-reference §5 / v167-gm-carplay-spec）。 */
    private val cinemoServices=setOf("_carplay-ctrl._tcp.","_mfi-config._tcp.")

    /** 判定按证据强度排序：实际能力探测 > features 位 > 服务集 > TXT 形态。
     *  任何一条都判不出时返回 [AirPlayGeneration.UNKNOWN]，调用方保持未选定认证流程，不通过失败来探测较弱路径。 */
    fun estimate(evidence:AirPlayGenerationEvidence):AirPlayGeneration {
        evidence.pairVerifyAvailable?.let{return if(it)AirPlayGeneration.V2 else AirPlayGeneration.V1}
        if(evidence.features.has(AirPlayFeature.HK_PAIRING_AND_ENCRYPT)||
            evidence.features.has(AirPlayFeature.SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL))return AirPlayGeneration.V2
        val normalized=evidence.services.map{it.lowercase()}
        if(normalized.any{it in v2OnlyServices})return AirPlayGeneration.V2
        if(normalized.any{it in cinemoServices})return AirPlayGeneration.V2
        if(evidence.protocolVersion?.trim()=="1.0")return AirPlayGeneration.V1
        if(evidence.protocolVersion!=null)return AirPlayGeneration.V1
        return AirPlayGeneration.UNKNOWN
    }

    fun cinemoStyle(services:Set<String>):Boolean=services.map{it.lowercase()}.any{it in cinemoServices}

    fun describe(generation:AirPlayGeneration)=when(generation){
        AirPlayGeneration.V1->"AirPlay 1 (/auth-setup only)"
        AirPlayGeneration.V2->"AirPlay 2 (HAP /pair-setup+/pair-verify)"
        AirPlayGeneration.UNKNOWN->"SCHEMA_UNRESOLVED"
    }
}

/** Authentication exchanges are selected once; a failure does not authorize a downgrade. */
enum class PairStrategy { HAP_PAIR_SETUP_VERIFY, MFI_SAP_AUTH_SETUP }

object PairStrategies {
    /** An estimated generation never selects an authentication exchange (docs/05). */
    fun order(@Suppress("UNUSED_PARAMETER") generation:AirPlayGeneration):List<PairStrategy> =emptyList()
}
