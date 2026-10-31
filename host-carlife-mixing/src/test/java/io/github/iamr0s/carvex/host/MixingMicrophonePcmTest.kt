package io.github.iamr0s.carvex.host

import io.github.iamr0s.carvex.pcm.PcmFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class MixingMicrophonePcmTest {
    private fun pcm(values:IntArray):ByteArray = ByteBuffer.allocate(values.size*2)
        .order(ByteOrder.LITTLE_ENDIAN).also{buffer->values.forEach{buffer.putShort(it.toShort())}}.array()
    private fun samples(pcm:ByteArray):IntArray = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        .let{buffer->IntArray(pcm.size/2){buffer.short.toInt()}}
    private fun tone(rate:Int,frames:Int)=IntArray(frames){(12000*sin(2*PI*1000*it/rate)).roundToInt()}

    @Test fun mono24kKeepsPhaseAcrossBlocksAndPreservesOneKhzDuration(){
        val source=PcmFormat(24000,1)
        val raw=pcm(tone(source.rate,2400))
        val whole=MixingMicrophonePcm().use{it.convert(raw,source,Any())}
        val split=MixingMicrophonePcm()
        val binding=Any()
        val joined=ByteArrayOutputStream()
        try{
            var at=0;var block=0
            while(at<raw.size){
                val bytes=minOf((1+block%23)*2,raw.size-at)
                joined.write(split.convert(raw.copyOfRange(at,at+bytes),source,binding))
                at+=bytes;block++
            }
        }finally{split.close()}
        assertArrayEquals(whole,joined.toByteArray())
        val output=samples(whole)
        assertEquals(1600,output.size)
        val rising=(1 until output.size).count{output[it-1]<=0&&output[it]>0}
        assertTrue("1 kHz must retain about 100 cycles in 100 ms; got $rising",rising in 99..100)
    }

    @Test fun stereo48kBecomes16kMonoWithBothChannelsMixed(){
        val source=PcmFormat(48000,2)
        val raw=pcm(IntArray(4800*2){index->if(index%2==0)12000 else -4000})
        val output=MixingMicrophonePcm().use{samples(it.convert(raw,source,Any()))}
        assertEquals(1600,output.size)
        assertTrue(output.all{it==4000})
    }

    @Test fun aNewBindingClearsPreviousSamplesAndFractionalPhase(){
        val converter=MixingMicrophonePcm()
        val source=PcmFormat(24000,1)
        try{
            val old=Any();val replacement=Any()
            converter.convert(pcm(intArrayOf(-20000,20000)),source,old)
            assertArrayEquals(pcm(intArrayOf(3000)),converter.convert(pcm(intArrayOf(3000)),source,replacement))
            // A second block in the same binding must continue at the pending fractional position.
            assertEquals(0,converter.convert(pcm(intArrayOf(9000)),source,replacement).size)
            assertArrayEquals(pcm(intArrayOf(12000)),converter.convert(pcm(intArrayOf(15000)),source,replacement))
        }finally{converter.close()}
    }

    @Test fun aSourceFormatChangeResetsHistoryEvenInTheSameBinding(){
        val converter=MixingMicrophonePcm();val binding=Any()
        try{
            converter.convert(pcm(intArrayOf(-20000,20000)),PcmFormat(24000,1),binding)
            assertArrayEquals(pcm(intArrayOf(6000)),converter.convert(pcm(intArrayOf(10000,2000)),PcmFormat(48000,2),binding))
        }finally{converter.close()}
    }

    @Test fun native16kContentIsUnchangedAndResetAllowsAFreshPhase(){
        val converter=MixingMicrophonePcm();val binding=Any()
        val raw=pcm(intArrayOf(0,32767,-32768,1234,-5678))
        try{
            assertArrayEquals(raw,converter.convert(raw,MixingMicrophonePcm.OUTPUT,binding))
            val source=PcmFormat(24000,1)
            converter.convert(pcm(intArrayOf(-20000,20000)),source,binding)
            converter.reset()
            assertArrayEquals(pcm(intArrayOf(3000)),converter.convert(pcm(intArrayOf(3000)),source,binding))
        }finally{converter.close()}
        assertThrows(IllegalStateException::class.java){converter.convert(raw,MixingMicrophonePcm.OUTPUT,binding)}
    }
}

