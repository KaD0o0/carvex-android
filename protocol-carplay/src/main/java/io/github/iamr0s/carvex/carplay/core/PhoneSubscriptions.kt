package io.github.iamr0s.carvex.carplay.core

/**
 * 电话/通信状态上报的**订阅状态**（第 15 章 §2.1–§2.3）。
 *
 * 车机用 `4154`（通话状态）与 `4157`（通信状态）订阅，`4156`/`4159` 退订；订阅生效后手机才应
 * 异步发 `4155`/`4158`。本实现目前**没有通话状态来源**，所以这里只做"接受订阅 + 记录字段集"，
 * 不主动上报——把消息从"未知命令"变成"已接受"，为后续接入通话状态留出接口。
 */
object PhoneSubscriptions {
    /** `4154` 的字段集合上限（固件 CFSet 容量 13）。 */
    const val CALL_FIELDS_MAX = 13
    /** 默认集：无参数、或只列了 id 5 时追加 `{0,1,2,3,4}`。 */
    val DEFAULT_CALL_FIELDS = setOf(0, 1, 2, 3, 4)

    @Volatile var callStateFields: Set<Int> = emptySet(); private set
    @Volatile var callStateActive: Boolean = false; private set
    /** 只列了 id 5（或完全无参数）时为真：固件置 `feature+0x08`。 */
    @Volatile var callStateDefaultSet: Boolean = false; private set
    @Volatile var sawParam5: Boolean = false; private set

    @Volatile var communicationFields: Set<Int> = emptySet(); private set
    @Volatile var communicationActive: Boolean = false; private set

    /** `4154`：N 个空载荷参数，id ∈ 0..0x0c；id 越界只忽略，载荷非空则整条失败。返回是否被接受。 */
    fun startCallStateUpdates(fields: IapFields): Boolean {
        val ids = mutableSetOf<Int>()
        var saw5 = false
        var sawNon5 = false
        for ((id, payload) in fields.ordered) {
            if (id > 0x0c) continue // 固件：未知 id 记日志后跳过，不失败
            if (payload.isNotEmpty()) return false // 固件：非空载荷 → 整条失败，订阅保持原样
            ids += id
            if (id == 5) saw5 = true else sawNon5 = true
        }
        callStateDefaultSet = !sawNon5
        if (callStateDefaultSet) ids += DEFAULT_CALL_FIELDS
        callStateFields = ids
        sawParam5 = saw5
        callStateActive = true
        return true
    }

    /** `4156`：无参数，退订通话状态。 */
    fun stopCallStateUpdates() {
        callStateActive = false
        callStateFields = emptySet()
        callStateDefaultSet = false
        sawParam5 = false
    }

    /** `4157`：通信状态订阅，语义同 `4154` 的"空载荷参数/越界忽略/非空失败"。 */
    fun startCommunicationUpdates(fields: IapFields): Boolean {
        val ids = mutableSetOf<Int>()
        for ((id, payload) in fields.ordered) {
            if (id > 0x11) continue
            if (payload.isNotEmpty()) return false
            ids += id
        }
        communicationFields = ids
        communicationActive = true
        return true
    }

    /** `4159`：退订通信状态。 */
    fun stopCommunicationUpdates() {
        communicationActive = false
        communicationFields = emptySet()
    }

    /** `4155` 的状态快照；字段只在已订阅时出现（ch15 §2.13）。 */
    data class CallState(
        val remoteId: String = "",
        val displayName: String = "",
        val status: Int = 0,
        val direction: Int = 0,
        val callUuid: String? = null,
        val addressBookId: String? = null,
        val label: String? = null,
        val service: Int? = null,
        val conferenced: Boolean = false,
        val conferenceGroup: Int? = null,
        val disconnectReason: Int? = null,
        val startTimestamp: Long? = null,
    )

    /** legacy 表：status 1..6 → 3,3,1,1,2,1，越界 0（ch15 §3）。 */
    fun legacyStatus(status: Int): Int {
        val table = intArrayOf(3, 3, 1, 1, 2, 1)
        return if (status in 1..6) table[status - 1] else 0
    }

    /** legacy 方向：1→0、2→1、其它→2。 */
    fun legacyDirection(direction: Int): Int = when (direction) {
        1 -> 0
        2 -> 1
        else -> 2
    }

    fun callStateUpdate(subscribed: Set<Int>, state: CallState): List<Pair<Int, ByteArray>> {
        if (!callStateActive) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        fun add(id: Int, value: ByteArray) {
            if (id in subscribed) out += id to value
        }
        add(0, IapWire.cstring(state.remoteId))
        add(1, IapWire.cstring(state.displayName))
        add(2, byteArrayOf(legacyStatus(state.status).toByte()))
        add(3, byteArrayOf(legacyDirection(state.direction).toByte()))
        val uuid = state.callUuid
        if (!uuid.isNullOrEmpty()) {
            add(4, IapWire.cstring(uuid))
            state.addressBookId?.takeIf { it.isNotEmpty() }?.let { add(6, IapWire.cstring(it)) }
            state.label?.takeIf { it.isNotEmpty() }?.let { add(7, IapWire.cstring(it)) }
            state.service?.let { add(8, byteArrayOf(it.toByte())) }
            add(9, byteArrayOf(legacyDirection(state.direction).toByte()))
            if (state.conferenced && state.conferenceGroup != null) add(0x0a, byteArrayOf(state.conferenceGroup.toByte()))
            if (state.status == 0 && state.disconnectReason != null) add(0x0b, byteArrayOf(state.disconnectReason.toByte()))
            state.startTimestamp?.takeIf { it != 0L }?.let { add(0x0c, IapWire.be64(it)) }
        }
        return out
    }

    /** `4158` 的通信状态快照；未订阅的 id 不出现，缺省值来自平台默认字典（ch15 §2.14）。 */
    data class Communications(
        val signalStrength: Int = 0,
        val registrationStatus: Int = 0,
        val airplaneModeStatus: Int = 0,
        val ttyStatus: Int = 0,
        val carrierName: String = "",
        val cellularSupported: Boolean = false,
        val telephonyEnabled: Boolean = false,
        val faceTimeAudioEnabled: Boolean = false,
        val faceTimeVideoEnabled: Boolean = false,
        val muteStatus: Boolean = false,
        val currentCallCount: Int = 0,
        val newVoicemailCount: Int = 0,
        val initiateCallAvailable: Boolean = false,
        val endAndAcceptAvailable: Boolean = false,
        val holdAndAcceptAvailable: Boolean = false,
        val swapAvailable: Boolean = false,
        val mergeAvailable: Boolean = false,
        val holdAvailable: Boolean = false,
    )

    fun communicationUpdate(subscribed: Set<Int>, state: Communications): List<Pair<Int, ByteArray>> {
        if (!communicationActive) return emptyList()
        val u8 = mapOf(0 to state.signalStrength, 1 to state.registrationStatus, 2 to state.airplaneModeStatus,
            3 to state.ttyStatus, 0x0a to state.currentCallCount, 0x0b to state.newVoicemailCount)
        val bools = mapOf(5 to state.cellularSupported, 6 to state.telephonyEnabled,
            7 to state.faceTimeAudioEnabled, 8 to state.faceTimeVideoEnabled, 9 to state.muteStatus,
            0x0c to state.initiateCallAvailable, 0x0d to state.endAndAcceptAvailable,
            0x0e to state.holdAndAcceptAvailable, 0x0f to state.swapAvailable,
            0x10 to state.mergeAvailable, 0x11 to state.holdAvailable)
        val out = mutableListOf<Pair<Int, ByteArray>>()
        fun add(id: Int, value: ByteArray) {
            if (id in subscribed) out += id to value
        }
        for ((id, value) in u8) add(id, byteArrayOf(value.toByte()))
        add(4, IapWire.cstring(state.carrierName))
        for ((id, value) in bools) add(id, byteArrayOf(if (value) 1 else 0))
        return out
    }
}
