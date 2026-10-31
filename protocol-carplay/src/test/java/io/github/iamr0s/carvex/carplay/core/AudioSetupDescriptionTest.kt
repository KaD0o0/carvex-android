package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AudioSetupDescriptionTest {
    @Test fun pcmFormatAdvertisesTheFiveMillisecondRtpPacketSize(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x40L}
        val fields=AudioSetupDescription.playback(CarPlayAudioPlan.assistant,pcm,7,5001)
        assertEquals(120,fields["spf"])
        assertEquals(5001,fields["controlPort"])
        assertEquals(true,fields["supportsHighAccuracyTimestamps"])
        assertFalse(fields.containsKey("input"))
        assertFalse(fields.containsKey("dataPort"))
        val wire=Plist.dictionary(Plist.encode(mapOf("streams" to listOf(fields))))
        val stream=(wire["streams"] as List<*>).single() as Map<*,*>
        assertEquals(120,(stream["spf"] as Number).toInt())
        assertEquals(7,(stream["streamConnectionID"] as Number).toInt())
    }
    @Test fun inputFieldsAppearOnlyWithAnActualLocalInputPort(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x40L}
        val fields=AudioSetupDescription.playback(CarPlayAudioPlan.assistant,pcm,8,5001,5002)
        assertEquals(true,fields["input"])
        assertEquals(5002,fields["dataPort"])
        assertEquals(0x40L,fields["audioFormat"])
    }
    @Test fun compressedFormatRetainsItsCodecPacketSize(){
        val aac=CarPlayAudioPlan.formats.single{it.bit==0x800000L}
        val fields=AudioSetupDescription.playback(CarPlayAudioPlan.media,aac,9,5001)
        assertEquals(1024,fields["spf"])
        assertEquals(102,fields["type"])
        assertFalse(fields.containsKey("supportsRTPPacketRedundancy"))
        assertFalse(fields.containsKey("sr"))
        assertFalse(fields.containsKey("ct"))
    }
    @Test fun setupFieldBaselineIsKeptDuringIdentityRepair(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x40L}
        val fields=AudioSetupDescription.playback(CarPlayAudioPlan.assistant,pcm,14,5001,5002)
        assertEquals(setOf("type","audioType","audioFormat","streamConnectionID",
            "audioLatencyMs","controlPort","spf","supportsHighAccuracyTimestamps",
            "supportsRTPPacketRedundancy","input","dataPort"),fields.keys)
    }
    @Test fun wirelessRaw100And101OfferRedButRaw102DoesNot(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x40L}
        val main=AudioSetupDescription.playback(CarPlayAudioPlan.assistant,pcm,10,5001)
        assertEquals(true,main["supportsRTPPacketRedundancy"])
        val alt=AudioSetupDescription.playback(CarPlayAudioPlan.alternate,pcm,11,5001)
        assertEquals(true,alt["supportsRTPPacketRedundancy"])
        val media=AudioSetupDescription.playback(CarPlayAudioPlan.media,pcm,12,5001)
        assertFalse(media.containsKey("supportsRTPPacketRedundancy"))
    }
    @Test fun usbCallerCanSuppressRedOffer(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x40L}
        val fields=AudioSetupDescription.playback(CarPlayAudioPlan.assistant,pcm,13,5001,offerRedundancy=false)
        assertFalse(fields.containsKey("supportsRTPPacketRedundancy"))
    }

    @Test fun speechNegotiationOffersRedAndPreservesCodecClockAndDuplexFields(){
        val routes=listOf(CarPlayAudioPlan.alert,CarPlayAudioPlan.alternate,CarPlayAudioPlan.assistant,
            CarPlayAudioPlan.Route(3,106,"speechRecognition"))
        val formats=CarPlayAudioPlan.formats.filter{it.bit in setOf(0x40L,0x20000000L,0x40000000L)}
        fun onWire(fields:Map<String,Any>):Map<*,*> {
            val root=Plist.dictionary(Plist.encode(mapOf("streams" to listOf(fields))))
            return (root["streams"] as List<*>).single() as Map<*,*>
        }
        for(route in routes)for(format in formats){
            val inputPorts=if(route==CarPlayAudioPlan.assistant)listOf(null,5002) else listOf(null)
            for(inputPort in inputPorts){
                val normal=AudioSetupDescription.playback(route,format,15,5001,inputPort,
                    offerRedundancy=AudioRedundancyPolicy.offerFor(route))
                assertEquals(true,normal["supportsRTPPacketRedundancy"])
                val wire=onWire(normal)
                assertEquals(true,wire["supportsRTPPacketRedundancy"])
                assertEquals(format.bit,(wire["audioFormat"] as Number).toLong())
                assertEquals(if(format.codec==CarPlayAudioPlan.Codec.PCM16)format.rate/200 else format.framesPerPacket,
                    (wire["spf"] as Number).toInt())
                assertEquals(true,wire["supportsHighAccuracyTimestamps"])
                if(inputPort==null){
                    assertFalse(wire.containsKey("input"));assertFalse(wire.containsKey("dataPort"))
                }else{
                    assertEquals(true,wire["input"])
                    assertEquals(inputPort,(wire["dataPort"] as Number).toInt())
                }
            }
        }
    }

    @Test fun musicSetupKeepsItsExistingRoutePolicy(){
        val pcm=CarPlayAudioPlan.formats.single{it.bit==0x4000L}
        for(route in listOf(CarPlayAudioPlan.media,CarPlayAudioPlan.mediaLowLatency)){
            val explicit=AudioSetupDescription.playback(route,pcm,16,5001,
                offerRedundancy=AudioRedundancyPolicy.offerFor(route))
            val existing=AudioSetupDescription.playback(route,pcm,16,5001)
            assertArrayEquals(Plist.encode(existing),Plist.encode(explicit))
            assertEquals(route==CarPlayAudioPlan.mediaLowLatency,
                explicit["supportsRTPPacketRedundancy"]==true)
        }
    }
}
