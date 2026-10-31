package io.github.iamr0s.carvex.carplay.core
/** 无线配对的 CSM 消息（catplay-cross-reference §5.1/§5.2）。
 *
 * 证据等级 B：消息名与 ID 来自第三方生成体（Apple 规格档案），但与**实证**行为互证 ——
 * t3-carplay-spec §5.2 记录 T3 车机在 OOBB 配对里回 `OOBBTPairingAccessoryInformation`（P-192/P-256），
 * 与 §5.1 的 `0x00B1` 对得上；两份材料合起来说明"先配对、再给凭据"是真实存在的一条路。
 *
 * 本实现**没有**实现 OOBB / 蓝牙配对（P-192/P-256 的密钥交换），所以这里的存在意义是"识别"：
 * 收到这些消息说明这台车机要走"先配对再下发凭据"的路，而当前只实现了"车机直接下发 Wi-Fi 凭据"
 * 那一条。静默忽略会让这种情况表现成**握手超时**，看不出真正原因 —— 这正是要避免的。
 */
object IapWirelessPairing {
    enum class Message(val id:Int,val label:String) {
        START_OOBB(0x00b0,"StartOOBBTPairing"),
        OOBB_ACCESSORY_INFORMATION(0x00b1,"OOBBTPairingAccessoryInformation"),
        OOBB_LINK_KEY_INFORMATION(0x00b2,"OOBBTPairingLinkKeyInformation"),
        OOBB_COMPLETION_INFORMATION(0x00b3,"OOBBTPairingCompletionInformation"),
        STOP_OOBB(0x00b4,"StopOOBBTPairing"),
        START_BLUETOOTH_PAIRING(0x0b00,"StartBluetoothPairing"),
        BLUETOOTH_ACCESSORY_INFORMATION(0x0b01,"BluetoothPairingAccessoryInformation"),
        BLUETOOTH_PAIRING_STATUS(0x0b02,"BluetoothPairingStatus"),
        STOP_BLUETOOTH_PAIRING(0x0b03,"StopBluetoothPairing");

        companion object {
            private val byId=values().associateBy{it.id}
            fun at(id:Int):Message?=byId[id]
            val ids:Set<Int> get()=byId.keys
        }
    }

    fun describe(id:Int)=Message.at(id)?.let{it.label+"(0x"+Integer.toHexString(it.id)+")"}

    /** 车机在请求一条本实现没做的配对路径时应给出的说明（谁来记、怎么记由调用方决定）。 */
    fun unsupported(id:Int)=describe(id)?.let{
        "${"receiver requested wireless pairing "}${it}${": this build does not implement OOBB/Bluetooth pairing (P-192/P-256 key exchange, catplay 5.1/5.2, t3 5.2); "}"+
            "${"if it waits for pairing to finish before sending Wi-Fi credentials, this session stalls at the handshake timeout"}"
    }
}
