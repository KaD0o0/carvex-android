package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test

class AudioRetransmitProtocolTest {
    @Test fun capturedStructureFieldsAreBigEndian(){
        val request=byteArrayOf(0x80.toByte(),0xd5.toByte(),0,1,0xff.toByte(),0xfe.toByte(),0,3)
        assertEquals(AudioRetransmitProtocol.Request(65534,3),AudioRetransmitProtocol.request(request))
        assertArrayEquals(request,AudioRetransmitProtocol.request(65534,3))
    }
    @Test fun responsePreservesOddSizedEncryptedRtpWithoutPadding(){
        val rtp=MediaAudioPackets.accessUnit(byteArrayOf(1,2,3),ByteArray(32),65535,12,4,102)
        val response=AudioRetransmitProtocol.response(rtp)
        assertEquals(rtp.size+4,response.size)
        assertArrayEquals(rtp,response.copyOfRange(4,response.size))
        assertArrayEquals(byteArrayOf(1,2,3),Crypto.open(ByteArray(32),Crypto.nonce(4),response.copyOfRange(16,response.size-8),response.copyOfRange(8,16)))
    }
    @Test fun wrongVersionLengthAndEmptyRequestsDoNotTriggerReplies(){
        val valid=AudioRetransmitProtocol.request(7,1)
        assertNull(AudioRetransmitProtocol.request(valid.copyOfRange(0,7)))
        assertNull(AudioRetransmitProtocol.request(valid.copyOf().also{it[3]=3}))
        assertNull(AudioRetransmitProtocol.request(valid.copyOf().also{it[6]=0;it[7]=0}))
        assertNull(AudioRetransmitProtocol.request(valid.copyOf().also{it[0]=0x40}))
    }
}
