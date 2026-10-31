package io.github.iamr0s.carvex.carplay.core
import io.github.iamr0s.carvex.pcm.PcmFormat

/** MediaCodec output assembly only. No RTP, ADTS, codec cookie or CarPlay encryption is inferred. */
class AudioEncoderOutput(private val maximumBytes:Int=1024*1024) {
    data class Unit(val bytes:ByteArray,val presentationTimeUs:Long,val endOfStream:Boolean)
    private var pending=java.io.ByteArrayOutputStream()
    private var timestamp:Long?=null
    init{require(maximumBytes>0)}
    fun append(bytes:ByteArray,ptsUs:Long,partial:Boolean,endOfStream:Boolean):Unit? {
        require(!(partial&&endOfStream)){"Incomplete audio access unit at EOS"}
        require(!(endOfStream&&bytes.isEmpty()&&pending.size()>0)){"Missing final audio fragment before EOS"}
        require(pending.size().toLong()+bytes.size<=maximumBytes){"Encoded audio exceeds local assembly budget"}
        if(bytes.isNotEmpty()){
            val previous=timestamp
            require(previous==null||previous==ptsUs){"Audio fragments have inconsistent timestamps"}
            if(previous==null)timestamp=ptsUs
            pending.write(bytes)
        }
        if(partial)return null
        if(pending.size()==0)return if(endOfStream)Unit(byteArrayOf(),ptsUs,true) else null
        return Unit(pending.toByteArray(),requireNotNull(timestamp),endOfStream).also{clear()}
    }
    fun clear(){pending.reset();timestamp=null}
}

/** Input PTS is a sample clock, not the time at which a Binder block happens to be processed. */
class AudioInputClock(private val format:PcmFormat,private val originUs:Long=0) {
    private var frames=0L
    init{require(originUs>=0)}
    val timeUs:Long get()=Math.addExact(originUs,Math.multiplyExact(frames,1_000_000L)/format.rate)
    fun advance(bytes:Int):Long {
        require(bytes>=0&&bytes%(format.channels*2)==0)
        val start=timeUs
        frames=Math.addExact(frames,bytes.toLong()/(format.channels*2))
        return start
    }
}
