package io.github.iamr0s.carvex.carplay.core
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AirPlay PT213/214 packet structure, distinct from RFC4585 generic NACK. */
object AudioRetransmitProtocol {
    data class Request(val start:Int,val count:Int)
    fun request(bytes:ByteArray):Request? {
        if(bytes.size !in setOf(8,16)||bytes[0]!=0x80.toByte()||(bytes[1].toInt() and 255)!=213)return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val declared=((b.getShort(2).toInt() and 65535)+1)*4
        if(declared!=bytes.size)return null
        val count=b.getShort(6).toInt() and 65535
        return if(count>0)Request(b.getShort(4).toInt() and 65535,count) else null
    }
    fun request(start:Int,count:Int):ByteArray {
        require(start in 0..65535&&count in 1..65535)
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(213.toByte())
            .putShort(1).putShort(start.toShort()).putShort(count.toShort()).array()
    }
    fun response(original:ByteArray):ByteArray {
        require(original.size in 12..1440)
        // This receiver version reads RTP using the actual datagram length. Padding would alter AEAD payload.
        val reply=ByteArray(4+original.size)
        ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(214.toByte())
            .putShort((reply.size/4-1).toShort())
        original.copyInto(reply,4)
        return reply
    }
    /** 单个缺包的否定确认：线上字段是 `captured - 1`（大端）后跟 0 计数。
     *  缺 100 报 0x0063，不是 0x0064；错一位会把对端的重传窗口整体挪动。 */
    fun failure(sequence:Int):ByteArray {
        require(sequence in 0..65535)
        val captured=(sequence-1) and 0xffff
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(214.toByte())
            .putShort(1).putShort(captured.toShort()).putShort(0).array()
    }
}
