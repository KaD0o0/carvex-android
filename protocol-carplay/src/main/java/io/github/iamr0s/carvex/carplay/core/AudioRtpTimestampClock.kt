package io.github.iamr0s.carvex.carplay.core

/** Wire sample positions are separate from local source/pacing deadlines.
 * AirPlaySender 24A446's ordinary main/alt writer uses a cumulative packet-sample
 * counter unless SETUP's reply explicitly enables high-accuracy timestamps.
 * Keep the old source-PTS path for that reply and for type102 (a separate engine
 * in the firmware). This does NOT implement the firmware's network-clock anchors.
 * Confined to one writer. Only a successful primary UDP send commits samples;
 * discarded/suppressed candidates and retransmissions do not consume them. */
internal class AudioRtpTimestampClock(streamType:Int,receiverHighAccuracyTimestamps:Boolean,
    private val initialTimestamp:Long=0L) {
    enum class Mode(val diagnosticName:String) {
        CONTINUOUS_SAMPLES("continuous-samples"),
        LEGACY_SOURCE_PTS("legacy-source-pts")
    }
    val mode=if(streamType in setOf(100,101)&&!receiverHighAccuracyTimestamps)
        Mode.CONTINUOUS_SAMPLES else Mode.LEGACY_SOURCE_PTS
    private var sentSamples=0L
    init{require(streamType in setOf(100,101,102)&&initialTimestamp in 0..0xffffffffL)}

    // Retain an extended Long for RED age calculations; serialization wraps to uint32.
    fun timestamp(sourceTimestamp:Long):Long {
        require(sourceTimestamp>=0)
        return Math.addExact(initialTimestamp,if(mode==Mode.CONTINUOUS_SAMPLES)sentSamples else sourceTimestamp)
    }
    fun sent(samples:Int) {
        require(samples>0)
        sentSamples=Math.addExact(sentSamples,samples.toLong())
    }

    companion object {
        // Missing, false and non-boolean replies cannot enable this capability.
        fun receiverSupportsHighAccuracy(response:Map<*,*>):Boolean=
            response["supportsHighAccuracyTimestamps"]==true
    }
}
