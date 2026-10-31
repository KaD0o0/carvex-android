package io.github.iamr0s.carvex.host

import io.github.iamr0s.carvex.pcm.Pcm16Converter
import io.github.iamr0s.carvex.pcm.PcmFormat

/** The mixing microphone callback uses 16 kHz mono PCM16.
 * Keep resampling phase within one listener binding; Binder delivery belongs outside this lock. */
internal class MixingMicrophonePcm:AutoCloseable {
    private var binding:Any?=null
    private var converter:Pcm16Converter?=null
    private var closed=false

    @Synchronized fun convert(pcm:ByteArray,source:PcmFormat,binding:Any):ByteArray {
        check(!closed)
        require(source.accepts(pcm.size))
        if(this.binding!==binding||converter?.source!=source){
            this.binding=binding
            converter=Pcm16Converter(source,OUTPUT)
        }
        return requireNotNull(converter).convert(pcm)
    }

    @Synchronized fun reset(){binding=null;converter=null}
    @Synchronized override fun close(){closed=true;reset()}

    companion object {
        val OUTPUT=PcmFormat(16000,1,2)
        /** Android input channel configuration in the original CarLife mix callback. */
        const val CHANNEL_CONFIG=4
    }
}
