package io.github.iamr0s.carvex.carplay.core

import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/**
 * type=130 专用 iAP2 流（第 13 章 §2.6b）。
 *
 * 固件线格式（`iap-rmcl-routing24.md`，依据 `0x24fda7bbc`/`0x24fda5468`/`0x24fda2b9c`）：
 *   BE32 总长 | BE32 "asyn" | BE64 group | BE32 "comm" | BE64 replyToken | BE32 status | Data
 * 外层再包一层 `LE16 length | ciphertext | tag16` 的记录加密，手机写方向用
 * `DataStream-Output-Encryption-Key`（盐 `DataStream-Salt<十进制 seed>`）。
 *
 * 24A446 的 iAP RCS 配置为 raw Data + no-reply；no-reply formatter 固定 package type
 * `asyn`。当前 CarVeX 发送 `group=0`、`replyToken=0`，但原始 parcel 构造默认值和
 * 协商来源尚未闭合，不能把这两个值写成固件语义。
 *
 * 反方向（车机→手机）不走这条 TCP，而是事件通道的 `iAPSendMessage`（固件 IR-01/02）。
 */
class IapTunnel(
    output: OutputStream,
    key: ByteArray,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val record = RecordOutput(output, key)
    private val lock = Any()
    private var closed = false
    private var sent = 0

    /** 发一个 iAP2 包。`group` 是当前本地编码参数；原始来源仍为 OPEN。 */
    fun send(data: ByteArray, group: Long = 0): Boolean = synchronized(lock) {
        if (closed) return false
        return try {
            val packet = packageBytes(data, group)
            record.write(packet)
            record.flush()
            sent++
            // 只在前几包和后每 50 包打一行：确认写入真的发生，又不会把日志刷满。
            if (sent <= 5 || sent % 50 == 0)
                log("iAP tunnel tx n=" + sent + " len=" + packet.size + " group=" + group)
            true
        } catch (t: Exception) {
            log("iAP tunnel write failed: " + t.javaClass.simpleName + " " + (t.message ?: ""))
            false
        }
    }

    override fun close() {
        synchronized(lock) { if (!closed) { closed = true; runCatching { record.close() } } }
    }

    companion object {
        const val CLIENT_TYPE_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val PACKAGE_TYPE_ASYNC = 0x6173796e // "asyn"
        private const val MESSAGE_TYPE_COMM = 0x636f6d6d // "comm"
        const val HEADER_BYTES = 32

        /** 32 字节包头 + 原始 iAP2 字节；不在这里解释 CSM/链路层。
         * `group=0` 与 `replyToken=0` 是当前本地策略，不是已证明的固件默认值。 */
        fun packageBytes(data: ByteArray, group: Long = 0): ByteArray {
            require(data.isNotEmpty())
            val out = ByteArray(HEADER_BYTES + data.size)
            putBe32(out, 0, out.size)
            putBe32(out, 4, PACKAGE_TYPE_ASYNC)
            putBe64(out, 8, group)
            putBe32(out, 16, MESSAGE_TYPE_COMM)
            putBe64(out, 20, 0) // replyToken：当前 no-reply 本地编码；上游默认值 OPEN
            putBe32(out, 28, 0) // raw status
            data.copyInto(out, HEADER_BYTES)
            return out
        }

        private fun putBe32(o: ByteArray, at: Int, v: Int) {
            o[at] = (v ushr 24).toByte(); o[at + 1] = (v ushr 16).toByte()
            o[at + 2] = (v ushr 8).toByte(); o[at + 3] = v.toByte()
        }

        private fun putBe64(o: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) o[at + i] = (v ushr (56 - 8 * i)).toByte()
        }
    }
}

/**
 * 24A446 creates the RCS channel ID with the CFString format `%@-RCS-%d`;
 * the first argument is the endpoint identity passed into RCS creation.
 * The counter is process-wide there; CarVeX has one runtime RCS per AirPlay
 * session, so its connection-local value starts at 1. The exact endpoint
 * identity supplied by carplayd remains OPEN.
 */
object IapTunnelSetupRequest {
    /**
     * 24A446 only creates the RCS after the SETUP response's enabledFeatures
     * array contains the exact "iAPChannel" capability string.
     */
    fun enabledBy(enabledFeatures:List<*>?):Boolean =
        enabledFeatures?.any{it=="iAPChannel"}==true

    fun build(deviceId:String,seed:Long,clientUUID:String=UUID.randomUUID().toString(),
              counter:Int=1):Map<String,Any?> {
        require(deviceId.isNotBlank()){"Type130 device ID is empty"}
        require(counter>0){"Type130 channel counter must be positive"}
        val canonicalClientUUID=clientUUID.uppercase(Locale.US)
        return linkedMapOf(
            "streams" to listOf(linkedMapOf(
                "type" to 130,
                "clientTypeUUID" to IapTunnel.CLIENT_TYPE_UUID,
                "clientUUID" to canonicalClientUUID,
                "channelID" to deviceId+"-RCS-"+counter,
                "controlType" to 2,
                "wantsDedicatedSocket" to true,
                "sendMessageAsIs" to true,
                "seed" to seed)))
    }
}
