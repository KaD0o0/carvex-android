package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.Assert.*
import org.junit.Test
class AudioCapabilitiesTest {
    @Test fun playbackUsesActualOutputMaskAndRetainsStreamPurpose(){
        val entries=AudioCapabilities.parse(listOf(mapOf("type" to 100,"audioType" to "media","audioInputFormats" to 0x10L,"audioOutputFormats" to 0x8000L)))
        val candidate=AudioCapabilities.playback(entries,PcmFormat(48000,2)).single()
        assertEquals(0x8000L,candidate.formatBit);assertEquals("media",candidate.entry.audioType)
        assertTrue(AudioCapabilities.playback(entries,PcmFormat(16000,1)).isEmpty())
        assertEquals(PcmFormat(16000,1),AudioCapabilities.capture(entries).single().pcm)
    }
    @Test fun missingOrMalformedMasksDoNotInventCapabilities(){
        val entries=AudioCapabilities.parse(listOf(mapOf("type" to 100,"audioOutputFormats" to "32768"),mapOf("type" to 101,"audioOutputFormats" to -1)))
        assertTrue(AudioCapabilities.playback(entries,PcmFormat(48000,2)).isEmpty())
        assertTrue(AudioCapabilities.parse(null).isEmpty())
        assertTrue(AudioCapabilities.parse(listOf(mapOf("type" to 100.5))).isEmpty())
    }
    @Test fun unknownCodecBitsArePreservedWithoutPretendingToSupportThem(){
        val mask=0x8000L or 0x800000L
        val entries=AudioCapabilities.parse(listOf(mapOf("type" to 106,"audioOutputFormats" to mask)))
        assertEquals(mask,entries.single().outputMask)
        assertEquals(1,AudioCapabilities.playback(entries,PcmFormat(48000,2)).size)
    }
}
