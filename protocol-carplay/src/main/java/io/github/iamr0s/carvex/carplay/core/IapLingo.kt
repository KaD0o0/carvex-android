package io.github.iamr0s.carvex.carplay.core
import java.io.ByteArrayOutputStream

/** iAP lingo 消息包 —— iap2-frame-spec §10（`create_iap_packet` @0x17884）。
 *
 * 它与 §1 的链路帧是两层：链路帧做传输封装，本包是消息本体，也是认证消息的载体。
 * 校验只覆盖本包自身且**不含首字节 0x55**（`dst[1] .. dst[total-2]`），与链路帧的头/payload
 * 双段校验不是一回事，两者不可混用。
 *
 * 长度口径取 §10.2 的公式：`body = lingo(1) + command(1 或 2) + [transaction(2)] + payload`，
 * 其中 lingo 4（[LINGO_GENERAL]）用 16 位大端 command，其余用 8 位。
 * §10.3 的还原代码把 command 的指针步进写成 2/3 字节，比长度公式多 1 字节 —— 两者不能同时成立
 * （否则写入字节数会超过 `total`，校验和落到包外）。这里以长度公式为准，因为 `total` 要返回给
 * 上层做分帧，必须与真实写入字节数自洽。
 */
object IapLingo {
    const val START=0x55
    /** command 为 16 位大端的 lingo；见 §10.2 的 body_len 分支。 */
    const val LINGO_GENERAL=4
    private const val SHORT_LIMIT=0xfc

    data class Message(val lingo:Int,val command:Int,val transaction:Int?,val payload:ByteArray) {
        override fun equals(other:Any?)=other is Message&&other.lingo==lingo&&other.command==command&&
                other.transaction==transaction&&other.payload.contentEquals(payload)
        override fun hashCode()=((lingo*31+command)*31+(transaction?:-1))*31+payload.contentHashCode()
    }

    fun isLingo(bytes:ByteArray)=bytes.isNotEmpty()&&(bytes[0].toInt() and 255)==START

    fun build(lingo:Int,command:Int,payload:ByteArray=byteArrayOf(),transaction:Int?=null):ByteArray {
        require(lingo in 0..255){"${"Invalid iAP lingo "}${lingo}"}
        require(command in 0..0xffff){"${"Invalid iAP command "}${command}"}
        if(lingo!=LINGO_GENERAL)require(command<=0xff){"${"lingo "}${lingo}${" carries an 8-bit command only"}"}
        transaction?.let{require(it in 0..0xffff){"${"Invalid iAP transaction "}${it}"}}
        val body=1+(if(lingo==LINGO_GENERAL)2 else 1)+payload.size+(if(transaction!=null)2 else 0)
        require(body<=0xffff){"${"iAP lingo body "}${body}${" exceeds the 16-bit length field"}"}
        val short=body<=SHORT_LIMIT
        val total=if(short)body+3 else body+5
        val out=ByteArrayOutputStream(total-1)
        out.write(START)
        if(short)out.write(body) else{out.write(0);out.write(body ushr 8);out.write(body and 0xff)}
        out.write(lingo)
        if(lingo==LINGO_GENERAL){out.write(command ushr 8);out.write(command and 0xff)} else out.write(command and 0xff)
        transaction?.let{out.write(it ushr 8);out.write(it and 0xff)}
        out.write(payload,0,payload.size)
        val data=out.toByteArray()
        require(data.size==total-1){"${"iAP lingo layout mismatch: wrote "}${data.size}${", expected "}${total-1}"}
        var sum=0
        for(i in 1 until data.size)sum+=data[i].toInt() and 255
        return data+byteArrayOf((-sum).toByte())
    }

    /** [hasTransaction] 无法从包内判断（§10.3 读的是全局标志 `g_iap_mode`），
     *  必须由知道当前协商状态的调用方给出。 */
    fun parse(bytes:ByteArray,hasTransaction:Boolean=false):Message {
        require(bytes.size>=4){"iAP lingo packet too short"}
        require(isLingo(bytes)){"Not an iAP lingo packet"}
        val short=(bytes[1].toInt() and 255)!=0
        val body=if(short)bytes[1].toInt() and 255 else IapWire.u16(bytes,2)
        val total=if(short)body+3 else body+5
        require(total==bytes.size){"${"iAP lingo length mismatch: declared "}${total}${", got "}${bytes.size}"}
        var sum=0
        for(i in 1 until total)sum+=bytes[i].toInt() and 255
        require(sum and 0xff==0){"Bad iAP lingo checksum"}
        var p=if(short)2 else 4
        val lingo=bytes[p++].toInt() and 255
        val command:Int
        if(lingo==LINGO_GENERAL){command=((bytes[p].toInt() and 255) shl 8) or (bytes[p+1].toInt() and 255);p+=2}
        else command=bytes[p++].toInt() and 255
        var transaction:Int?=null
        if(hasTransaction){transaction=IapWire.u16(bytes,p);p+=2}
        return Message(lingo,command,transaction,bytes.copyOfRange(p,total-1))
    }
}
