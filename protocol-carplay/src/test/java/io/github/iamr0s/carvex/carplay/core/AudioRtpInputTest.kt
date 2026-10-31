package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test

class AudioRtpInputTest {
    private val key=ByteArray(32){it.toByte()}
    private fun packet(seq:Int,nonce:Long)=MediaAudioPackets.accessUnit(byteArrayOf(1,2),key,seq,seq*240L,nonce,100)
    @Test fun inputUsesTimestampAndSsrcAadAndDropsDuplicates(){
        val input=AudioRtpInput(key)
        assertEquals(7,input.open(packet(7,7))!!.sequence)
        assertNull(input.open(packet(7,7)))
        assertEquals(6,input.open(packet(6,6))!!.sequence)
        assertEquals(8,input.open(packet(8,8))!!.sequence)
    }
    @Test fun failedAuthenticationDoesNotAdvanceReplayState(){
        val input=AudioRtpInput(key)
        val forged=packet(300,300).also{it[12]=(it[12].toInt() xor 1).toByte()}
        assertThrows(org.bouncycastle.crypto.InvalidCipherTextException::class.java){input.open(forged)}
        assertEquals(1,input.open(packet(1,1))!!.sequence)
    }
    @Test fun alteredSequenceCannotBypassAuthenticatedNonceReplayWindow(){
        val input=AudioRtpInput(key)
        val wire=packet(7,7)
        assertNotNull(input.open(wire))
        // RTP seq is outside this schema's AAD. It cannot be the replay identity.
        wire[2]=0;wire[3]=99
        assertNull(input.open(wire))
        assertNotNull(input.open(packet(100,100)))
        assertNull(input.open(packet(35,35)))
        assertNull(input.open(packet(36,36)))
        assertNotNull(input.open(packet(37,37)))
    }
    @Test fun malformedHeaderAndTruncatedTrailerNeverYieldAFrame(){
        val input=AudioRtpInput(key)
        val wire=packet(1,1)
        for(size in 0 until 36){
            assertThrows(IllegalArgumentException::class.java){input.open(wire.copyOf(size))}
        }
        for(header in listOf(0x40,0x81,0x90,0xa0)){
            val invalid=wire.copyOf().also{it[0]=header.toByte()}
            assertThrows(IllegalArgumentException::class.java){input.open(invalid)}
        }
        val wrongType=wire.copyOf().also{it[1]=101}
        assertThrows(IllegalArgumentException::class.java){input.open(wrongType)}
        assertNotNull(input.open(wire))
    }
    private fun frame(seq:Int)=AudioRtpInput.Frame(seq,seq*240L,byteArrayOf(1))
    @Test fun shortReorderingDoesNotLoseMissingFrameOrFlushDecoder(){
        val jitter=AudioInputJitter()
        jitter.offer(frame(65535),0);assertEquals(65535,jitter.poll(0)!!.sequence)
        jitter.offer(frame(1),1);assertNull(jitter.poll(2))
        assertEquals(0 to 1,jitter.missing())
        jitter.offer(frame(0),3);assertEquals(0,jitter.poll(3)!!.sequence);assertEquals(1,jitter.poll(3)!!.sequence)
        assertEquals(0L,jitter.skipped)
        assertFalse(jitter.offer(frame(0),4))
    }
    @Test fun deadlineSkipsMissingSamplesWithoutMovingBackwards(){
        val jitter=AudioInputJitter(waitNs=20)
        jitter.offer(frame(5),0);jitter.poll(0)
        jitter.offer(frame(8),1);assertNull(jitter.poll(20))
        assertEquals(8,jitter.poll(21)!!.sequence)
        assertEquals(2L,jitter.skipped)
    }
    @Test fun sampleClockUnwrapsRtpAndConvertsToMicroseconds(){
        val clock=RtpInputClock(24000)
        assertEquals(0L,clock.timeUs(0xffffff00L))
        assertEquals(20000L,clock.timeUs(224L))
        assertThrows(IllegalArgumentException::class.java){clock.timeUs(223L)}
    }
}
