package io.github.iamr0s.carvex.pcm

/** CarWith PCM contract; rate/channels always come from the actual callback/negotiation. */
data class PcmFormat(val rate:Int,val channels:Int,val encoding:Int=2) {
    init{require(rate in 8000..192000&&channels in 1..2&&encoding==2)}
    fun accepts(bytes:Int)=bytes in 1..32768&&bytes%(channels*2)==0
}
