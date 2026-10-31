package io.github.iamr0s.carvex.carplay.core
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Authenticated MainAudio packets for the current modern client build's 8-byte AAD profile.
 *  接收方向同时接受 main(PT100) 与 mred(PT104)；RED 头是明文，块区整体加密（ch08 §3.4）。
 *
 *  FRONTLANDER 的首个输入包（len=140, PT=104, 头部 `80689505 00000960 00000000 64`, nonce=0）在
 *  文档布局 + Input key + AAD[4,12) 下认证失败，说明密钥/AAD 至少有一项与文档不同。AEAD 认证是确定的：
 *  这里只尝试少量固定组合，只有认证通过的才发布，并记录命中的组合；未认证数据绝不进解码器。 */
class AudioRtpInput(private val key:ByteArray,private val type:Int=100,
    private val alternateKey:ByteArray?=null,private val log:(String)->Unit={}) {
    data class Frame(val sequence:Int,val timestamp:Long,val payload:ByteArray)
    private var highest=-1L
    private var seen=0L
    /** 首次认证成功后固定下来，后续包只试这个组合。 */
    private var chosen:Combo?=null
    private var announced=false
    private data class Combo(val start:Int,val outputKey:Boolean,val aad:String)

    private fun redHeaderLength(packet:ByteArray,pt:Int):Int {
        if(pt!=104)return 0
        var off=12
        while(true){
            require(off<packet.size){"Truncated RED header"}
            val more=(packet[off].toInt() and 0x80)!=0
            if(!more)return off+1-12
            require(packet.size-off>=4){"Truncated RED header"}
            off+=4
        }
    }

    private fun combos(redLen:Int):List<Combo>{
        val starts=listOf(12+redLen,12,14+redLen)
        val aads=listOf("4-12","0-12","0-start","none")
        val out=mutableListOf<Combo>()
        for(start in starts)for(outKey in listOf(false,true))for(aad in aads)out+=Combo(start,outKey,aad)
        return out
    }
    private fun aadFor(name:String,packet:ByteArray,start:Int):ByteArray=when(name){
        "4-12"->packet.copyOfRange(4,12)
        "0-12"->packet.copyOfRange(0,12)
        "0-start"->packet.copyOfRange(0,minOf(start,packet.size))
        else->byteArrayOf()
    }

    fun open(packet:ByteArray):Frame? {
        require(packet.size>=36&&packet[0]==0x80.toByte()){"Unexpected microphone RTP header"}
        val pt=packet[1].toInt() and 127
        require(pt==type||pt==104){"Unexpected microphone RTP header"}
        val redLen=redHeaderLength(packet,pt)
        val nonceBytes=packet.copyOfRange(packet.size-8,packet.size)
        val n=ByteBuffer.wrap(nonceBytes).order(ByteOrder.LITTLE_ENDIAN).long
        require(n>=0){"Nonce outside supported counter lifetime"}
        if(highest>=0&&n<=highest&&(highest-n>=64||seen and (1L shl (highest-n).toInt())!=0L))return null
        val all=combos(redLen)
        val order=if(chosen!=null)listOf(chosen!!)+all.filter{it!=chosen} else all
        var clear:ByteArray?=null;var used:Combo?=null;var last:Exception?=null
        for(combo in order){
            if(combo.start>=packet.size-8)continue
            val candidateKey=if(combo.outputKey)alternateKey?:key else key
            try{
                clear=Crypto.open(candidateKey,Crypto.nonce(n),packet.copyOfRange(combo.start,packet.size-8),
                    aadFor(combo.aad,packet,combo.start))
                used=combo
                break
            }catch(t:Exception){last=t}
        }
        val opened=clear?:throw (last?:IllegalStateException("Microphone packet could not be authenticated"))
        if(used!=chosen){
            chosen=used
            if(!announced){announced=true;log("Microphone decrypt combo start="+used!!.start+" key="+(if(used.outputKey)"output" else "input")+" aad="+used.aad)}
        }
        if(n>highest){val delta=n-highest;seen=if(highest<0||delta>=64)1L else (seen shl delta.toInt()) or 1;highest=n}
        else seen=seen or (1L shl (highest-n).toInt())
        val start=used!!.start
        val payload=if(pt==104&&start!=12){
            // RED 头明文（12 起的描述区）：块顺序排列，主块占剩余；历史块由 jitter 去重。
            var off=12;var skip=0
            while(true){
                val more=(packet[off].toInt() and 0x80)!=0
                if(!more)break
                val len=((packet[off+2].toInt() and 3) shl 8) or (packet[off+3].toInt() and 255)
                skip+=len;off+=4
            }
            require(skip<=opened.size){"RED block overruns payload"}
            opened.copyOfRange(skip,opened.size)
        }else if(pt==104){
            // RED 头被一起加密：从明文里按同样的 4 字节结构跳过历史块。
            var off=0;var skip=0
            while(true){
                require(off<opened.size){"Truncated decrypted RED header"}
                val more=(opened[off].toInt() and 0x80)!=0
                if(!more)break
                require(opened.size-off>=4){"Truncated decrypted RED header"}
                val len=((opened[off+2].toInt() and 3) shl 8) or (opened[off+3].toInt() and 255)
                skip+=len;off+=4
            }
            require(skip<=opened.size){"RED block overruns payload"}
            opened.copyOfRange(skip,opened.size)
        }else opened
        return Frame(((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255),ByteBuffer.wrap(packet,4,4).int.toLong() and 0xffffffffL,payload)
    }
}

/** Ordered bounded microphone input. Late duplicates do not rewind sequence state or flush a decoder. */
class AudioInputJitter(private val waitNs:Long=20_000_000L,private val capacity:Int=32) {
    private val pending=mutableMapOf<Int,AudioRtpInput.Frame>()
    private var expected:Int?=null
    private var missingSince:Long?=null
    var skipped=0L;private set
    init{require(waitNs>=0&&capacity>0)}
    fun offer(frame:AudioRtpInput.Frame,now:Long):Boolean {
        val next=expected?:frame.sequence.also{expected=it}
        val ahead=(frame.sequence-next) and 65535
        if(ahead>=32768||pending.containsKey(frame.sequence))return false
        if(pending.size>=capacity){
            if(ahead!=0)return false
            val farthest=pending.keys.maxByOrNull{(it-next) and 65535}
            if(farthest!=null)pending.remove(farthest)
        }
        pending[frame.sequence]=frame
        if(ahead>0&&missingSince==null)missingSince=now
        return true
    }
    fun missing():Pair<Int,Int>? {
        val next=expected?:return null
        if(pending.isEmpty()||pending.containsKey(next))return null
        val count=pending.keys.minOf{(it-next) and 65535}
        return next to count
    }
    fun poll(now:Long):AudioRtpInput.Frame? {
        var next=expected?:return null
        val gap=missing()
        if(gap!=null){
            if(pending.size<capacity&&now-(missingSince?:now)<waitNs)return null
            skipped+=gap.second;next=(next+gap.second) and 65535;expected=next
        }
        val frame=pending.remove(next)?:return null
        expected=(next+1) and 65535
        missingSince=if(missing()!=null)now else null
        return frame
    }
}

/** Unwrap ordered RTP sample timestamps before converting to codec microseconds. */
class RtpInputClock(private val rate:Int) {
    private var last:Long?=null
    private var samples=0L
    init{require(rate>0)}
    fun timeUs(timestamp:Long):Long {
        require(timestamp in 0..0xffffffffL)
        val previous=last
        if(previous!=null){
            val delta=(timestamp-previous) and 0xffffffffL
            require(delta<0x80000000L){"Ordered microphone timestamp moved backwards"}
            samples=Math.addExact(samples,delta)
        }
        last=timestamp
        return Math.multiplyExact(samples,1_000_000L)/rate
    }
}
