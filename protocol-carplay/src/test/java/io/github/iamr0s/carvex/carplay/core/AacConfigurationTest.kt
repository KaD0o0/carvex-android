package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test

class AacConfigurationTest {
    private fun bytes(vararg value:Int)=value.map{it.toByte()}.toByteArray()
    @Test fun lc48000StereoCanonicalConfig(){
        val format=CarPlayAudioPlan.formats.single{it.codec==CarPlayAudioPlan.Codec.AAC_LC&&it.rate==48000}
        assertEquals(AacConfiguration.Description(2,48000,2,1024),AacConfiguration.validate(bytes(0x11,0x90),format))
        assertArrayEquals(bytes(0x11,0x90),AacConfiguration.decoderConfig(format))
    }
    @Test fun eld480And512AreNotInterchangeable(){
        val format=CarPlayAudioPlan.formats.single{it.codec==CarPlayAudioPlan.Codec.AAC_ELD&&it.rate==48000&&it.channels==1}
        assertEquals(AacConfiguration.Description(39,48000,1,480),AacConfiguration.validate(bytes(0xf8,0xe6,0x30,0),format))
        assertArrayEquals(bytes(0xf8,0xe6,0x30,0),AacConfiguration.decoderConfig(format))
        assertEquals(512,AacConfiguration.parse(bytes(0xf8,0xe6,0x20,0)).frames)
        assertThrows(IllegalArgumentException::class.java){AacConfiguration.validate(bytes(0xf8,0xe6,0x20,0),format)}
    }
    @Test fun truncatedConfigurationFailsBeforeNetworkUse(){
        assertThrows(IllegalArgumentException::class.java){AacConfiguration.parse(bytes(0xf8))}
    }
}
