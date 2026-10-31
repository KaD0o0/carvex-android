package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AudioRedundancyPolicyTest {
    private val extendedSpeech=CarPlayAudioPlan.Route(3,106,"speechRecognition")
    private val eligibleRoutes=listOf(CarPlayAudioPlan.mediaLowLatency,CarPlayAudioPlan.alert,
        CarPlayAudioPlan.alternate,CarPlayAudioPlan.assistant,extendedSpeech)

    @Test fun responseFlagControlsEligibleRaw100And101(){
        assertTrue(AudioRedundancyPolicy.responseEnables(CarPlayAudioPlan.assistant,
            mapOf("supportsRTPPacketRedundancy" to true),offered=true))
        assertFalse(AudioRedundancyPolicy.responseEnables(CarPlayAudioPlan.assistant,
            emptyMap<String,Any?>(),offered=true))
        assertTrue(AudioRedundancyPolicy.responseEnables(CarPlayAudioPlan.alternate,
            mapOf("supportsRTPPacketRedundancy" to true),offered=true))
    }
    @Test fun raw102NeverUsesRedEvenIfPeerClaimsIt(){
        for(offered in listOf(false,true))assertFalse(AudioRedundancyPolicy.responseEnables(
            CarPlayAudioPlan.media,mapOf("supportsRTPPacketRedundancy" to true),offered))
    }

    @Test fun implementedRoutesOfferRedWithoutSpeechOverrides(){
        for(route in eligibleRoutes+CarPlayAudioPlan.media){
            assertEquals("route=$route",route.type!=102,AudioRedundancyPolicy.offerFor(route))
        }
    }

    @Test fun everyOfferedEligibleRouteStillRequiresPeerAcceptance(){
        for(route in eligibleRoutes){
            assertTrue(AudioRedundancyPolicy.responseEnables(route,
                mapOf("supportsRTPPacketRedundancy" to true),offered=true))
            assertFalse(AudioRedundancyPolicy.responseEnables(route,
                mapOf("supportsRTPPacketRedundancy" to false),offered=true))
            assertFalse(AudioRedundancyPolicy.responseEnables(route,emptyMap<String,Any>(),offered=true))
        }
    }

    @Test fun unsolicitedRedIsRejectedInsteadOfSilentlySendingPlainOrEnablingRed(){
        // This also covers a future USB caller that explicitly offers no RED.
        for(route in eligibleRoutes){
            assertThrows(UnsupportedOperationException::class.java){
                AudioRedundancyPolicy.responseEnables(route,
                    mapOf("supportsRTPPacketRedundancy" to true),offered=false)
            }
            assertFalse(AudioRedundancyPolicy.responseEnables(route,
                mapOf("supportsRTPPacketRedundancy" to false),offered=false))
            assertFalse(AudioRedundancyPolicy.responseEnables(route,emptyMap<String,Any>(),offered=false))
        }
    }

    @Test fun routeAndResponseMatrixNeverProducesAnUnadvertisedPacketFormat(){
        for(route in eligibleRoutes+CarPlayAudioPlan.media){
            val offered=AudioRedundancyPolicy.offerFor(route)
            for(accepted in listOf<Boolean?>(null,false,true)){
                val response=accepted?.let{mapOf("supportsRTPPacketRedundancy" to it)}?:emptyMap()
                if(route.type!=102&&!offered&&accepted==true){
                    assertThrows(UnsupportedOperationException::class.java){
                        AudioRedundancyPolicy.responseEnables(route,response,offered)
                    }
                }else{
                    assertEquals(offered&&accepted==true,
                        AudioRedundancyPolicy.responseEnables(route,response,offered))
                }
            }
        }
    }
}
