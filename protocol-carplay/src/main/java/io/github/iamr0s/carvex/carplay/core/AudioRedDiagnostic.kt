package io.github.iamr0s.carvex.carplay.core

/** Single-variable isolation, not a negotiated capability or a permanent codec policy.
 * Keep RED/PT/AEAD/nonce unchanged; only omit history for negotiated speech Opus RED. */
internal class AudioRedDiagnostic(val configured:Boolean,category:Int,codec:CarPlayAudioPlan.Codec,
    private val redEnabled:Boolean) {
    val applied=configured&&redEnabled&&category in setOf(2,3)&&codec==CarPlayAudioPlan.Codec.OPUS

    fun packet(payload:ByteArray,history:ByteArray?,historyTimestamp:Long?,key:ByteArray,
        sequence:Int,timestamp:Long,nonce:Long,type:Int):ByteArray =
        if(redEnabled)MediaAudioPackets.redPacket(payload,if(applied)null else history,
            if(applied)null else historyTimestamp,key,sequence,timestamp,nonce,type)
        else MediaAudioPackets.accessUnit(payload,key,sequence,timestamp,nonce,type)
}

/** Header/size/codec-TOC diagnostics only: never retain or render keys or audio payload. */
internal class AudioPacketDiagnostics(private val redEnabled:Boolean,private val codec:CarPlayAudioPlan.Codec,
    private val intervalNanos:Long=5_000_000_000L) {
    data class Metadata(val packetNumber:Long,val outerPt:Int,val innerPt:Int?,val packetBytes:Int,
        val auBytes:Int,val opusToc:Int?,val samples:Int,val sequence:Int,val timestamp:Long,
        val sequenceDelta:Int?,val timestampDelta:Long?,val historyOffset:Int?,val historyBytes:Int,
        val nonce:Long) {
        fun summary()="packet="+packetNumber+" outerPT="+outerPt+" innerPT="+(innerPt?:"none")+
            " packetBytes="+packetBytes+" auBytes="+auBytes+" opusTOC="+(opusToc?.let{"0x"+it.toString(16).padStart(2,'0')}?:"none")+
            " samples="+samples+" seq="+sequence+" timestamp="+timestamp+
            " seqDelta="+(sequenceDelta?:"none")+" timestampDelta="+(timestampDelta?:"none")+
            " historyOffset="+(historyOffset?:"none")+" historyBytes="+historyBytes+" nonce="+nonce
    }
    private var count=0L
    private var lastLoggedAt:Long?=null
    private var previousSequence:Int?=null
    private var previousTimestamp:Long?=null
    init{require(intervalNanos>0)}

    /** Call once after each successful send. Deltas refer to the preceding sent packet,
     * including packets that were not logged, and use the actual serialized RTP values. */
    fun sent(packet:ByteArray,accessUnit:ByteArray,samples:Int,nowNanos:Long):Metadata? {
        require(packet.size>=12+24&&accessUnit.isNotEmpty()&&samples>0)
        fun byte(index:Int)=packet[index].toInt() and 255
        val sequence=(byte(2) shl 8) or byte(3)
        val timestamp=(4..7).fold(0L){value,index->(value shl 8) or byte(index).toLong()}
        val sequenceDelta=previousSequence?.let{(sequence-it) and 65535}
        val timestampDelta=previousTimestamp?.let{(timestamp-it) and 0xffffffffL}
        previousSequence=sequence;previousTimestamp=timestamp;count++
        if(count>3&&lastLoggedAt?.let{nowNanos-it<intervalNanos}==true)return null
        lastLoggedAt=nowNanos
        var innerPt:Int?=null
        var historyOffset:Int?=null
        var historyBytes=0
        if(redEnabled){
            require(packet.size>=12+1+24)
            if(byte(12) and 128!=0){
                require(packet.size>=12+5+24)
                historyOffset=(byte(13) shl 6) or (byte(14) ushr 2)
                historyBytes=((byte(14) and 3) shl 8) or byte(15)
                innerPt=byte(16) and 127
            }else innerPt=byte(12) and 127
        }
        // The sender's explicit nonce is a nonnegative little-endian 64-bit counter.
        val nonce=(packet.size-1 downTo packet.size-8).fold(0L){value,index->(value shl 8) or byte(index).toLong()}
        return Metadata(count,byte(1) and 127,innerPt,packet.size,accessUnit.size,
            if(codec==CarPlayAudioPlan.Codec.OPUS)accessUnit[0].toInt() and 255 else null,
            samples,sequence,timestamp,sequenceDelta,timestampDelta,historyOffset,historyBytes,nonce)
    }
}
