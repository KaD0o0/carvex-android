package io.github.iamr0s.carvex.carplay.core

import java.util.UUID

/**
 * 手机侧普通 WiAP 的服务角色与公共 SDK 候选策略。
 *
 * 24A446 bluetoothd 的普通 WiAP 与 enableSink 是条件选择，不应同时发布两组角色。
 * 固件按完整 SDP attribute4 查询结果选服务；Android getUuids() 只是可能过时的提示，
 * 不能提供同等结论。本策略的第二候选是有界兼容探测，不是固件的精确 fallback。
 * 证据与平台缺口：research/connection/69、71；本类不做连接方向仲裁。
 */
internal object WiapBluetoothPolicy {
    enum class Service(
        val localUuid: UUID,
        val remoteUuid: UUID,
        val serviceName: String,
        val inboundLabel: String,
        val outboundLabel: String,
    ) {
        LEGACY(
            UUID.fromString("00000000-deca-fade-deca-deafdecacafe"),
            UUID.fromString("00000000-deca-fade-deca-deafdecacaff"),
            "Wireless iAP", "car", "phone",
        ),
        V2(
            UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77"),
            UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a78"),
            "Wireless iAP v2", "car-v2", "phone-v2",
        ),
    }

    // 这里只描述 UUID 缓存观察，不使用 ABSENT/VALID 等完整 SDP 记录结论。
    enum class CacheObservation { UNAVAILABLE, EMPTY, NO_WIAP_HINT, LEGACY_HINT, V2_HINT, BOTH_HINTS }
    enum class Basis { UUID_CACHE_HINT, PROBE_ON_UNKNOWN }

    data class Candidate(val service: Service, val basis: Basis)
    data class Plan(
        val observation: CacheObservation,
        val cachedUuidCount: Int?,
        val outbound: List<Candidate>,
    )

    fun plan(cachedUuids: Collection<UUID>?, listenOnly: Boolean): Plan {
        // 快照化输入，不让后续缓存变化悄悄改变本轮候选与日志依据。
        val snapshot = cachedUuids?.toSet()
        val legacy = snapshot?.contains(Service.LEGACY.remoteUuid) == true
        val v2 = snapshot?.contains(Service.V2.remoteUuid) == true
        val observation = when {
            snapshot == null -> CacheObservation.UNAVAILABLE
            snapshot.isEmpty() -> CacheObservation.EMPTY
            legacy && v2 -> CacheObservation.BOTH_HINTS
            v2 -> CacheObservation.V2_HINT
            legacy -> CacheObservation.LEGACY_HINT
            else -> CacheObservation.NO_WIAP_HINT
        }
        // 保留已有 legacy-only 提示的快路径。未提示 v2 不证明它不存在；反之亦然。
        // 没有任何远端 WiAP 提示时先探测 v2，不能因缓存中有 HFP/A2DP 就只拨 legacy。
        val order = if (legacy && !v2) listOf(Service.LEGACY, Service.V2)
                    else listOf(Service.V2, Service.LEGACY)
        val candidates = if (listenOnly) emptyList() else order.map { service ->
            Candidate(service, if (snapshot?.contains(service.remoteUuid) == true)
                Basis.UUID_CACHE_HINT else Basis.PROBE_ON_UNKNOWN)
        }
        return Plan(observation, snapshot?.size, candidates)
    }

    data class Listener<T>(val service: Service, val socket: T)

    /**
     * 与生产注册调用共用的顺序：legacy 成功后才注册 v2；不发布远端/Sink UUID。
     * open 返回 null 表示该次注册失败；异常由调用方决定是否为致命错误。
     * socket 的资源所有权仍归调用方，本函数不创建/关闭连接，也不承诺共用 channel。
     */
    fun <T : Any> openPhoneListeners(open: (Service) -> T?): List<Listener<T>> {
        val legacy = open(Service.LEGACY) ?: return emptyList()
        val listeners = mutableListOf(Listener(Service.LEGACY, legacy))
        open(Service.V2)?.let { listeners += Listener(Service.V2, it) }
        return listeners
    }
}
