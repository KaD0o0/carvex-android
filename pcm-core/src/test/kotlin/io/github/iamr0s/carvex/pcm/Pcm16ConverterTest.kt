package io.github.iamr0s.carvex.pcm

import org.junit.Assert.*
import org.junit.Test

class Pcm16ConverterTest {
    private fun pcm(values:IntArray)=java.nio.ByteBuffer.allocate(values.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN).also{b->values.forEach{b.putShort(it.toShort())}}.array()
    @Test fun resamplingPhaseSurvivesArbitraryBinderBlockBoundaries(){
        val input=pcm(IntArray(4410){(it%100)*200-10000})
        val source=PcmFormat(44100,1);val target=PcmFormat(48000,2)
        val whole=Pcm16Converter(source,target).convert(input)
        val converter=Pcm16Converter(source,target)
        val joined=java.io.ByteArrayOutputStream()
        for(at in input.indices step 34)joined.write(converter.convert(input.copyOfRange(at,minOf(at+34,input.size))))
        assertArrayEquals(whole,joined.toByteArray())
        assertEquals(4799,whole.size/4)
    }
    @Test fun stereoConversionPreservesSignAndDoesNotOverflow(){
        val converted=Pcm16Converter(PcmFormat(48000,2),PcmFormat(48000,1)).convert(pcm(intArrayOf(32767,32767,-32768,-32768,1000,-1000)))
        assertArrayEquals(pcm(intArrayOf(32767,-32768,0)),converted)
    }
    @Test fun exactFormatPreservesStereoAndRejectsPartialFrames(){
        val input=pcm(intArrayOf(123,-456))
        val converter=Pcm16Converter(PcmFormat(48000,2),PcmFormat(48000,2))
        assertArrayEquals(input,converter.convert(input))
        assertThrows(IllegalArgumentException::class.java){converter.convert(byteArrayOf(1,2))}
    }
    /** The buffered implementation must stay byte-for-byte identical to the naive per-sample writer. */
    @Test fun bufferedConversionMatchesNaiveReferenceByteForByte(){
        val combinations=listOf(
            PcmFormat(44100,1) to PcmFormat(48000,2),
            PcmFormat(44100,2) to PcmFormat(48000,2),
            PcmFormat(48000,2) to PcmFormat(44100,1),
            PcmFormat(16000,1) to PcmFormat(48000,1),
            PcmFormat(48000,2) to PcmFormat(48000,2),
            PcmFormat(8000,1) to PcmFormat(48000,2),
            PcmFormat(48000,1) to PcmFormat(48000,2),
            PcmFormat(192000,2) to PcmFormat(8000,1)
        )
        val random=java.util.Random(20261009L)
        for((source,target) in combinations){
            val frameBytes=source.channels*2
            val raw=ByteArray(source.rate/4*frameBytes).also{random.nextBytes(it)}
            val expected=ReferencePcm16Converter(source,target)
            val actual=Pcm16Converter(source,target)
            var at=0;var round=0
            while(at<raw.size){
                val frames=1+(round*7)%23
                val size=minOf(frames*frameBytes,raw.size-at)
                assertArrayEquals("source=$source target=$target at=$at",expected.convert(raw.copyOfRange(at,at+size)),actual.convert(raw.copyOfRange(at,at+size)))
                at+=size;round++
            }
            assertTrue("source=$source target=$target produced no output",at>0)
        }
    }
}

/** Straight copy of the previous implementation, kept only as the equivalence oracle. */
private class ReferencePcm16Converter(val source:PcmFormat,val target:PcmFormat) {
    private var index=-1L
    private var position=0L
    private var previous=IntArray(target.channels)
    fun convert(input:ByteArray):ByteArray {
        require(source.accepts(input.size))
        if(source==target)return input.copyOf()
        val output=java.io.ByteArrayOutputStream()
        fun write(samples:IntArray){for(sample in samples){output.write(sample and 255);output.write((sample shr 8) and 255)}}
        for(offset in input.indices step source.channels*2){
            val values=IntArray(source.channels){channel->
                val at=offset+channel*2
                ((input[at].toInt() and 255) or (input[at+1].toInt() shl 8)).toShort().toInt()
            }
            val current=when {
                source.channels==target.channels->values
                target.channels==1->intArrayOf((values[0]+values[1])/2)
                else->intArrayOf(values[0],values[0])
            }
            index++
            if(index==0L){write(current);position+=source.rate}
            else while(position<=index*target.rate){
                val fraction=position-(index-1)*target.rate
                val samples=IntArray(target.channels){channel->
                    (previous[channel]+(current[channel]-previous[channel])*fraction/target.rate).toInt().coerceIn(-32768,32767)
                }
                write(samples);position+=source.rate
            }
            previous=current
        }
        return output.toByteArray()
    }
}
