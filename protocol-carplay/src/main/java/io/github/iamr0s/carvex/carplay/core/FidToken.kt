package io.github.iamr0s.carvex.carplay.core
/** FID Token 包 —— iap2-frame-spec §4（识别阶段令牌，由配件侧构造）。
 *
 * 统一格式：`[总长度-1][0x00][类型][内容]`，即 `总长度 = 内容长度 + 3`。
 * 六型的"载荷布局"落在 [内容] 里。其中 0x02 与 0x04 的内容前面多一个字节
 * （0x02 是未写入的保留字节，0x04 是协议 ID），所以它们的总长是 `payload_len + 4` ——
 * 这与 §4.1 的表格一致，也与 §4.2 的逐包还原（`memcpy(dst+4, …)`）一致。
 *
 * 内容**不在这里解释字节序**：§4.2 显示 0x01 是整段 `memcpy`，序由调用方决定。
 * 当成原始字节处理，可以避免给对端加上一个文档里没有的假设。
 *
 * 解析对未知类型保持宽容。文档 §9 明确「FID token 各类型的触发时机」尚未确认，
 * 而我们要兼容的不止这一台车机的固件版本：别的版本完全可能发这里没有的类型，
 * 客户端必须照样切片并交给上层，而不是在识别阶段中断。
 */
object FidToken {
    const val ACCESSORY_CAPS=0x01
    const val ACCESSORY_INFO=0x02
    const val IPOD_PREFERENCE=0x03
    const val EA_PROTOCOL=0x04
    const val BUNDLE_SEEDID=0x05
    const val EA_PROTOCOL_METADATA=0x08
    private const val RESERVED=0x00
    private const val MIN_TOTAL=4
    private const val MAX_CONTENT=0xfd

    data class Token(val type:Int,val content:ByteArray) {
        override fun equals(other:Any?)=other is Token&&other.type==type&&other.content.contentEquals(content)
        override fun hashCode()=type*31+content.contentHashCode()
    }

    fun build(type:Int,content:ByteArray):ByteArray {
        require(type in 0..255){"${"Invalid FID token type "}${type}"}
        require(content.size<=MAX_CONTENT){"${"FID token content "}${content.size}${" exceeds the one-byte length field"}"}
        return byteArrayOf((content.size+2).toByte(),RESERVED.toByte(),type.toByte())+content
    }

    /** `0x01` 载荷固定 8 字节（§4.2：整段 `memcpy`）。 */
    fun accessoryCaps(caps:ByteArray)=build(ACCESSORY_CAPS,caps.also{require(it.size==8){"${"accessory_caps payload is fixed at 8 bytes, got "}${it.size}"}})

    /** `0x02` 内容 = 保留字节 + 变长 payload。 */
    fun accessoryInfo(payload:ByteArray)=build(ACCESSORY_INFO,byteArrayOf(RESERVED.toByte())+payload)

    /** `0x03` 载荷 = `u8` + `u8` + `0x01`，总长 6。 */
    fun ipodPreference(first:Int,second:Int)=build(IPOD_PREFERENCE,byteArrayOf(byte(first),byte(second),0x01))

    /** `0x04` 内容 = 协议 ID + 变长 payload。协议名/ID 的具体取值由对端决定，这里只透传。 */
    fun eaProtocol(protocolId:Int,payload:ByteArray)=build(EA_PROTOCOL,byteArrayOf(byte(protocolId))+payload)

    /** `0x05` 载荷固定 11 字节（`u32`+`u32`+`u16`+`u8`），总长 14。 */
    fun bundleSeedId(seedId:ByteArray)=build(BUNDLE_SEEDID,seedId.also{require(it.size==11){"${"bundle_seedid payload is fixed at 11 bytes, got "}${it.size}"}})

    /** `0x08` 载荷 = `u8` + `u8`，总长 5。 */
    fun eaProtocolMetadata(first:Int,second:Int)=build(EA_PROTOCOL_METADATA,byteArrayOf(byte(first),byte(second)))

    fun parse(bytes:ByteArray):Token {
        require(bytes.size>=MIN_TOTAL){"FID token too short"}
        val total=length(bytes,0)
        require(total==bytes.size){"${"FID token length mismatch: declared "}${total}${", got "}${bytes.size}"}
        return Token(bytes[2].toInt() and 255,bytes.copyOfRange(3,total))
    }

    /** 连续切分识别阶段连发的令牌；未知类型照切，交给调用方决定怎么用。 */
    fun split(bytes:ByteArray):List<Token> {
        val out=mutableListOf<Token>()
        var at=0
        while(at<bytes.size){
            require(bytes.size-at>=MIN_TOTAL){"${"Truncated FID token at "}${at}"}
            val total=length(bytes,at)
            require(at+total<=bytes.size){"${"FID token overruns its buffer at "}${at}"}
            out+=Token(bytes[at+2].toInt() and 255,bytes.copyOfRange(at+3,at+total))
            at+=total
        }
        return out
    }

    private fun length(bytes:ByteArray,at:Int):Int {
        require((bytes[at+1].toInt() and 255)==RESERVED){"${"Unexpected FID token reserved byte "}${bytes[at+1]}${" at "}${at}"}
        val total=(bytes[at].toInt() and 255)+1
        require(total>=MIN_TOTAL){"${"FID token declares "}${total}${" bytes, below the 4-byte minimum"}"}
        return total
    }

    private fun byte(value:Int):Byte {
        require(value in 0..255){"${"FID token field "}${value}${" is not a byte"}"}
        return value.toByte()
    }
}
