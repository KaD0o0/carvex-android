package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat

import org.junit.Assert.*
import org.junit.Test

class AudioEncoderOutputTest {
    @Test fun partialBuffersAreNotPublishedAsCompleteAudioPackets(){
        val assembler=AudioEncoderOutput()
        assertNull(assembler.append(byteArrayOf(1,2),100,true,false))
        val unit=assembler.append(byteArrayOf(3,4),100,false,false)!!
        assertArrayEquals(byteArrayOf(1,2,3,4),unit.bytes)
        assertEquals(100L,unit.presentationTimeUs);assertFalse(unit.endOfStream)
        assertArrayEquals(byteArrayOf(5),assembler.append(byteArrayOf(5),200,false,false)!!.bytes)
    }
    @Test fun incompleteAndInconsistentFragmentsAreRejected(){
        val assembler=AudioEncoderOutput(3)
        assembler.append(byteArrayOf(1,2),100,true,false)
        assertThrows(IllegalArgumentException::class.java){assembler.append(byteArrayOf(3),200,false,false)}
        assertThrows(IllegalArgumentException::class.java){assembler.append(byteArrayOf(3,4),100,false,false)}
        assertThrows(IllegalArgumentException::class.java){assembler.append(byteArrayOf(),200,false,true)}
        assembler.clear()
        assertTrue(assembler.append(byteArrayOf(),200,false,true)!!.endOfStream)
    }
    @Test fun finalAccessUnitAndCodecPrimingTimestampArePreserved(){
        val unit=AudioEncoderOutput().append(byteArrayOf(1),-21000,false,true)!!
        assertEquals(-21000L,unit.presentationTimeUs);assertTrue(unit.endOfStream)
        assertArrayEquals(byteArrayOf(1),unit.bytes)
    }
    @Test fun inputClockUsesSamplesAcross44100HzBlockBoundaries(){
        val clock=AudioInputClock(PcmFormat(44100,2),1000000)
        repeat(441){index->assertEquals(1000000L+index*100L*1000000L/44100,clock.advance(400))}
        assertEquals(2000000L,clock.timeUs)
        assertThrows(IllegalArgumentException::class.java){clock.advance(3)}
    }
}
