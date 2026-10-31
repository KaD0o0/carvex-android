package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test

class CompressedAudioContractTest {
    private val lc=CarPlayAudioPlan.formats.single{it.codec==CarPlayAudioPlan.Codec.AAC_LC&&it.rate==48000}
    @Test fun aacLcChecksActualRateChannelsAnd1024FrameFlag(){
        CompressedAudioContract.aacLcConfig(byteArrayOf(0x11,0x90.toByte()),lc)
        assertThrows(IllegalArgumentException::class.java){CompressedAudioContract.aacLcConfig(byteArrayOf(0x12,0x10),lc)}
        assertThrows(IllegalArgumentException::class.java){CompressedAudioContract.aacLcConfig(byteArrayOf(0x11,0x94.toByte()),lc)}
        assertThrows(IllegalArgumentException::class.java){CompressedAudioContract.aacLcConfig(byteArrayOf(0x11,0x88.toByte()),lc)}
    }
    @Test fun opusDurationUsesTocInsteadOfHardcoded20ms(){
        assertEquals(480,CompressedAudioContract.opusSamples(byteArrayOf(0x98.toByte(),0),24000))
        assertEquals(960,CompressedAudioContract.opusSamples(byteArrayOf(0x98.toByte(),0),48000))
        assertEquals(240,CompressedAudioContract.opusSamples(byteArrayOf(0x90.toByte(),0),24000))
        assertThrows(IllegalArgumentException::class.java){CompressedAudioContract.opusSamples(byteArrayOf(0x9b.toByte(),0),24000)}
    }
    @Test fun oddLengthCompressedPayloadIsNotPcmSwapped(){
        val raw=byteArrayOf(1,2,3)
        val wire=MediaAudioPackets.accessUnit(raw,ByteArray(32),4,5,6,101)
        assertEquals(101,wire[1].toInt() and 127)
        assertArrayEquals(raw,Crypto.open(ByteArray(32),Crypto.nonce(6),wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12)))
    }
}
