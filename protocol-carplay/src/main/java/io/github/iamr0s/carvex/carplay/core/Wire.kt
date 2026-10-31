package io.github.iamr0s.carvex.carplay.core
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unknown/repeatable parameters retain order; singleton callers reject ambiguity. */
data class IapFields(val ordered:List<Pair<Int,ByteArray>>){
    operator fun get(id:Int):ByteArray? {
        val values=ordered.filter{it.first==id}
        require(values.size<=1){"${"Duplicate singleton iAP field "}${id}"}
        return values.firstOrNull()?.second
    }
    fun isEmpty()=ordered.isEmpty()
}

object IapWire {
    data class Frame(val flags: Int, val seq: Int, val ack: Int, val session: Int, val payload: ByteArray)
    val detection = byteArrayOf(-1, 0x55, 2, 0, 0xee.toByte(), 0x10)
    fun be16(n: Int): ByteArray { require(n in 0..65535); return byteArrayOf((n ushr 8).toByte(), n.toByte()) }
    /** iAP2 的多字节字段一律大端定长：NowPlayingUpdate 的 duration/elapsed/queue 都是 u32。 */
    fun be32(n: Long): ByteArray { require(n in 0..0xffffffffL); return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) }
    fun be64(n: Long): ByteArray = ByteArray(8) { i -> (n ushr (56 - 8 * i)).toByte() }
    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
    fun check(b: ByteArray) = (-b.sumOf { it.toInt() and 255 }).toByte()
    /** Checksum over a window, so a parser holding one shared buffer need not copy a slice first. */
    fun valid(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): Boolean {
        var sum = 0
        for (i in offset until offset + length) sum += b[i].toInt() and 255
        return sum and 255 == 0
    }
    fun fields(values: List<Pair<Int, ByteArray>>) = ByteArrayOutputStream().apply {
        values.forEach { (id, data) -> write(be16(data.size+4)); write(be16(id)); write(data) }
    }.toByteArray()
    fun csm(id: Int, values: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val b = fields(values); return byteArrayOf(0x40,0x40) + be16(b.size+6) + be16(id) + b
    }
    /** Already-encoded CSM body. Ordinary 1D03 entries are empty TLVs and also work with [csm]. */
    fun csmRaw(id: Int, body: ByteArray): ByteArray {
        require(id in 0..0xffff){"CSM id out of range"}
        require(body.size <= 0xffff - 6){"CSM body too large"}
        return byteArrayOf(0x40,0x40) + be16(body.size+6) + be16(id) + body
    }
    fun parseFieldList(b:ByteArray):IapFields {
        require(b.size<=65535){"iAP parameters exceed size budget"}
        val out=mutableListOf<Pair<Int,ByteArray>>();var p=0
        while(p<b.size) {
            require(p+4<=b.size) { "Truncated iAP TLV" }
            val n=u16(b,p); require(n>=4 && p+n<=b.size)
            val id=u16(b,p+2)
            out+=id to b.copyOfRange(p+4,p+n);p+=n
        };return IapFields(out.toList())
    }
    fun parseFields(b: ByteArray): Map<Int, ByteArray> {
        val fields=parseFieldList(b);val out=linkedMapOf<Int,ByteArray>()
        for((id,value) in fields.ordered){
            require(id !in out){"${"Duplicate singleton iAP field "}${id}"}
            out[id]=value
        }
        return out
    }
    /** iAP2 packet checksum: versions 1/3 use type0, version2 uses type1. */
    fun checksum(packetVersion:Int, bytes:ByteArray):ByteArray {
        require(packetVersion in 1..3)
        if(packetVersion!=2)return byteArrayOf(check(bytes))
        var a=255;var b=255;var offset=0
        while(offset<bytes.size){
            val end=minOf(offset+20,bytes.size)
            while(offset<end){a+=bytes[offset].toInt() and 255;b+=a;offset++}
            a=((a ushr 8) and 255)+(a and 255)
            b=((b ushr 8) and 255)+(b and 255)
        }
        a=(a ushr 8)+(a and 255)
        val value=(0-(((b+(b shl 8)) and 0x3ff00) or a)) and 0xffff
        return byteArrayOf((value ushr 8).toByte(),value.toByte())
    }
    fun packet(f: Frame,packetVersion:Int=1): ByteArray {
        require(listOf(f.flags,f.seq,f.ack,f.session).all { it in 0..255 })
        require(f.payload.size<=65525&&packetVersion in 1..3)
        val checksumBytes=if(packetVersion==2)2 else 1
        val headerLength=8+checksumBytes
        val h=byteArrayOf(-1,0x5a)+be16(headerLength+if(f.payload.isEmpty()) 0 else f.payload.size+checksumBytes)+
            byteArrayOf(f.flags.toByte(),f.seq.toByte(),f.ack.toByte(),f.session.toByte())
        return h+checksum(packetVersion,h.copyOfRange(0,8))+if(f.payload.isEmpty()) byteArrayOf() else f.payload+checksum(packetVersion,f.payload)
    }
    fun exact(input: InputStream, count: Int): ByteArray {
        require(count in 0..4*1024*1024)
        val b=ByteArray(count); var p=0
        while(p<count) { val n=input.read(b,p,count-p); if(n<0) throw EOFException("${"Truncated "}${p}${"/"}${count}"); if(n==0) throw IOException("Zero read"); p+=n }; return b
    }
    fun read(input: InputStream, maxPacket: Int = 65535, packetVersion:Int=1): Frame {
        require(packetVersion in 1..3)
        val checksumBytes=if(packetVersion==2)2 else 1
        val headerLength=8+checksumBytes
        val h=exact(input,headerLength)
        require(h[0]==(-1).toByte() && h[1]==0x5a.toByte() &&
            checksum(packetVersion,h.copyOfRange(0,8)).contentEquals(h.copyOfRange(8,headerLength))) { "Bad iAP header" }
        val n=u16(h,2); require(n in headerLength..maxPacket) { "${"Bad iAP length "}${n}" }
        val b=exact(input,n-headerLength)
        require(b.isEmpty() || (b.size>checksumBytes&&checksum(packetVersion,b.copyOfRange(0,b.size-checksumBytes)).contentEquals(b.copyOfRange(b.size-checksumBytes,b.size)))) { "Bad iAP payload checksum" }
        return Frame(h[4].toInt() and 255,h[5].toInt() and 255,h[6].toInt() and 255,h[7].toInt() and 255,
            if(b.isEmpty()) b else b.copyOf(b.size-checksumBytes))
    }
    fun cstring(s: String)=s.toByteArray(Charsets.UTF_8)+byteArrayOf(0)
    fun text(b: ByteArray):String {
        val end=b.indexOf(0);require(end>=0){"iAP cstring missing NUL within TLV"}
        return b.copyOfRange(0,end).toString(Charsets.UTF_8)
    }
}
object Tlv8 {
    fun encode(vararg fields: Pair<Int,ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        fields.forEach { (t,b) ->
            require(t in 0..255); if(b.isEmpty()) { write(t); write(0) }
            var p=0; while(p<b.size) { val n=minOf(255,b.size-p); write(t); write(n); write(b,p,n); p+=n }
        }
    }.toByteArray()
    fun decode(b: ByteArray): Map<Int,ByteArray> {
        require(b.size<=65535); val out=linkedMapOf<Int,ByteArray>(); var p=0; var last=-1; var lastSize=-1
        while(p<b.size) {
            require(p+2<=b.size); val t=b[p++].toInt() and 255; val n=b[p++].toInt() and 255
            require(p+n<=b.size)
            // State and Error are single-byte values, never fragmented aggregate fields.
            if(t==6||t==7)require(n==1&&t !in out)
            if(t in out) require(t==last && lastSize==255) { "Invalid TLV8 repetition" }
            out[t]=(out[t]?:byteArrayOf())+b.copyOfRange(p,p+n); p+=n; last=t; lastSize=n
        }; return out
    }
    fun state(n: Int)=6 to byteArrayOf(n.toByte())
}
object VideoWire {
    const val HEADER_BYTES=128
    /** Local allocation budget, not an advertised receiver limit. */
    const val MAX_PAYLOAD_BYTES=4*1024*1024
    /** 屏幕流保活记录（第 07 章 §2.6.1）：128 字节明文头、`0x04=2`、无正文。
     *  固件在 `_screenstream_dequeueAndProcessSampleBuffer 0x2519b0014` 里构造，且**不经过加密**。 */
    fun keepAlive(): ByteArray = ByteArray(HEADER_BYTES).also{header->
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply{
            putInt(0,0)        // 0x00 正文长度
            put(4,2)          // 0x04 opcode = 2（无正文 keepalive）
            put(5,0)          // 0x05 标志位
            putFloat(124,0f)  // 0x7c 正文长度 f32
        }
    }
    fun config(avcc: ByteArray,w: Int,h: Int): ByteArray {
        require(w>0&&h>0&&avcc.isNotEmpty()&&avcc.size<=MAX_PAYLOAD_BYTES)
        val header=ByteArray(HEADER_BYTES); ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            // 配置记录（第 07 章 §2.6）：0x00=仅负载长度；0x04=1；0x06=AVC 基础 0x12 并置 0x04
            // （有 cryptor）；0x07=1。几何写在 0x10/0x14，源矩形是 0x20 与 0x30 各四个 float。
            putInt(0,avcc.size); put(4,1); put(6,(0x12 or 0x04).toByte()); put(7,1)
            putFloat(16,w.toFloat()); putFloat(20,h.toFloat())
            putFloat(40,w.toFloat()); putFloat(44,h.toFloat())
            putFloat(56,w.toFloat()); putFloat(60,h.toFloat())
        }; return header+avcc
    }
    /** 帧记录（第 07 章 §2.6）。0x08 是**已转换的 presentation NTP**，0x10 才是原始单调时间戳；
     *  0x05 的关键帧位来自 IDR；0x20/0x58 都放 capture NTP 的同一个值。 */
    fun frame(nals: ByteArray,key: ByteArray,counter: Long,monotonicNs: Long,
              presentationNtp: Long = 0, captureNtp: Long = 0, keyframe: Boolean = isKeyframe(nals)): ByteArray {
        require(nals.isNotEmpty()&&nals.size<=MAX_PAYLOAD_BYTES-16&&key.size==32&&counter>=0&&monotonicNs>=0)
        val h=ByteArray(HEADER_BYTES); ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0,nals.size+16)
            if(keyframe)put(5,0x10)
            putLong(8,presentationNtp)
            putLong(16,monotonicNs)
            putLong(32,captureNtp)
            putLong(88,captureNtp)
        }
        return h+Crypto.seal(key,Crypto.nonce(counter),nals,h)
    }
    /** AVCC 负载里第一个 NAL 的类型是 5 即 IDR。解析失败时不谎报关键帧。 */
    fun isKeyframe(nals: ByteArray): Boolean = runCatching{
        if(nals.size<5)return@runCatching false
        val n=ByteBuffer.wrap(nals,0,4).int
        n in 1..(nals.size-4) && (nals[4].toInt() and 0x1f)==5
    }.getOrDefault(false)
    fun ntp(millis: Long): Long = ((millis/1000+2208988800L) shl 32) or ((millis%1000)*0x100000000L/1000)
    fun annexBToLengths(b: ByteArray): ByteArray {
        return lengthPrefixed(splitNal(b))
    }
    fun lengthPrefixed(units:List<ByteArray>):ByteArray {
        var size=0L
        for(unit in units){require(unit.isNotEmpty());size+=4L+unit.size;require(size<=4*1024*1024)}
        val result=ByteArray(size.toInt());val output=ByteBuffer.wrap(result)
        for(unit in units){output.putInt(unit.size);output.put(unit)}
        return result
    }
    fun splitNal(b: ByteArray): List<ByteArray> {
        fun prefix(i:Int):Int = if(i+3<b.size && b[i]==0.toByte() && b[i+1]==0.toByte() && b[i+2]==0.toByte() && b[i+3]==1.toByte()) 4
            else if(i+2<b.size && b[i]==0.toByte() && b[i+1]==0.toByte() && b[i+2]==1.toByte()) 3 else 0
        require(b.isNotEmpty()&&b.size<=4*1024*1024)
        if(prefix(0)==0) {
            val out=mutableListOf<ByteArray>(); var p=0
            while(p<b.size) { require(p+4<=b.size); val n=ByteBuffer.wrap(b,p,4).int; p+=4; require(n>0&&n<=b.size-p); out+=b.copyOfRange(p,p+n); p+=n }; return out
        }
        val starts=mutableListOf<Pair<Int,Int>>(); var i=0
        while(i<b.size) { val n=prefix(i); if(n>0) { starts+=i to n; i+=n } else i++ }
        return starts.mapIndexed { idx,(p,n) -> val end=starts.getOrNull(idx+1)?.first?:b.size; require(end>p+n); b.copyOfRange(p+n,end) }
    }
    fun avcc(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size>=4 && (sps[0].toInt() and 31)==7 && pps.isNotEmpty() && (pps[0].toInt() and 31)==8)
        return byteArrayOf(1,sps[1],sps[2],sps[3],-1,0xe1.toByte())+IapWire.be16(sps.size)+sps+byteArrayOf(1)+IapWire.be16(pps.size)+pps
    }
}
