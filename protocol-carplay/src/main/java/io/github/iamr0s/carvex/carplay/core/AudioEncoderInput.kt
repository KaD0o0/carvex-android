package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat

/** A complete, bounded PCM callback copied before asynchronous codec processing.
 * Callback duration is not queue age: a fresh 120 ms speech block must not lose
 * its first 20 ms to the 100 ms queue-lateness policy. Reserve all source samples
 * before admission so rejected or expired blocks still leave their original gap. */
internal class AudioEncoderInput private constructor(
    val pcm:ByteArray?,val firstFrame:Long,val atNs:Long
) {
    fun expired(nowNanos:Long):Boolean=nowNanos-atNs>100_000_000L

    companion object {
        fun prepare(pcm:ByteArray,format:PcmFormat,clock:AudioSourceClock,atNs:Long):AudioEncoderInput {
            require(format.accepts(pcm.size))
            val reservation=clock.reserve(pcm.size/(format.channels*2),atNs)
            return AudioEncoderInput(pcm.copyOf(),reservation.firstFrame,atNs)
        }
        fun endOfStream(atNs:Long)=AudioEncoderInput(null,0,atNs)
    }
}
