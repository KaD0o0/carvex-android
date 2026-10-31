package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class AudioEncoderInputTest {
    @Test fun fresh120msSpeechBlocksRetainEverySampleAndAContinuousClock() {
        for(rate in listOf(16000,24000,48000)){
            val format=PcmFormat(rate,1)
            val clock=AudioSourceClock(rate)
            val frames=rate*120/1000
            val pcm=ByteArray(frames*2){(it%251).toByte()}
            repeat(50){index->
                val input=AudioEncoderInput.prepare(pcm,format,clock,index*120_000_000L)
                assertArrayEquals(pcm,input.pcm)
                assertEquals(index*frames.toLong(),input.firstFrame)
                assertEquals((index+1)*120_000L,clock.endPtsUs())
                assertFalse(input.expired(input.atNs))
            }
        }
    }

    @Test fun preparationCopiesTheWholeBlockBeforeTheHostReusesItsBuffer() {
        val format=PcmFormat(16000,1)
        val original=ByteArray(3840){it.toByte()}
        val expected=original.copyOf()
        val input=AudioEncoderInput.prepare(original,format,AudioSourceClock(format.rate),0)
        assertNotSame(original,input.pcm)
        original.fill(0)
        assertArrayEquals(expected,input.pcm)
    }

    @Test fun maximumInputSizeAndFrameAlignmentRemainBounded() {
        for(channels in listOf(1,2)){
            val format=PcmFormat(48000,channels)
            val clock=AudioSourceClock(format.rate)
            for(size in listOf(0,1,32769,32772)){
                assertThrows(IllegalArgumentException::class.java){
                    AudioEncoderInput.prepare(ByteArray(size),format,clock,0)
                }
            }
            if(channels==2)assertThrows(IllegalArgumentException::class.java){
                AudioEncoderInput.prepare(ByteArray(2),format,clock,0)
            }
            val maximum=ByteArray(32768){(it%251).toByte()}
            val input=AudioEncoderInput.prepare(maximum,format,clock,0)
            assertEquals(0L,input.firstFrame) // Invalid submissions did not advance the clock.
            assertArrayEquals(maximum,input.pcm)
            assertEquals(32768L/(channels*2)*1_000_000/48000,clock.endPtsUs())
        }
    }

    @Test fun rejectedAndExpiredBlocksDoNotCollapseTheReservedSourceTimeline() {
        val format=PcmFormat(48000,1)
        val clock=AudioSourceClock(format.rate)
        val queue=Channel<AudioEncoderInput>(1)
        val pcm=ByteArray(11520) // 120 ms, including all six 20 ms Opus input frames.
        val first=AudioEncoderInput.prepare(pcm,format,clock,0)
        val rejected=AudioEncoderInput.prepare(pcm,format,clock,120_000_000)
        try{
            assertTrue(queue.trySend(first).isSuccess)
            assertTrue(queue.trySend(rejected).isFailure)
            val expired=requireNotNull(queue.tryReceive().getOrNull())
            assertFalse(expired.expired(100_000_000))
            assertTrue(expired.expired(100_000_001))
            val next=AudioEncoderInput.prepare(pcm,format,clock,240_000_000)
            assertEquals(11520L,next.firstFrame)
            assertEquals(360_000L,clock.endPtsUs())
            assertTrue(queue.trySend(next).isSuccess)
            assertSame(next,queue.tryReceive().getOrNull())
        }finally{queue.cancel()}
    }

    @Test fun endOfStreamRemainsAnExplicitMarkerWithoutPcm() {
        val end=AudioEncoderInput.endOfStream(123)
        assertNull(end.pcm)
        assertEquals(0L,end.firstFrame)
        assertEquals(123L,end.atNs)
    }
}
