package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.CarPlayAudioPlan
import io.github.iamr0s.carvex.pcm.PcmFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioDemandGateTest {
    private val format=PcmFormat(16000,1)
    private class Stream(category:Int):NegotiatedAudioTransport {
        var closed=false
        override val ready get()=!closed
        override val formats=mapOf(category to PcmFormat(16000,1))
        override val microphoneFormat:PcmFormat?=null
        override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){}
        override fun close(){closed=true}
    }

    @Test fun announcementsEmptyAndMalformedPcmCannotClaimASetup(){
        val gate=AudioDemandGate()
        for(category in 1..3){
            assertNull(gate.claim(category,0,format))
            assertNull(gate.claim(category,1,format))
            assertNull(gate.claim(category,32770,format))
        }
        assertNull(gate.claim(0,320,format))
        assertNull(gate.claim(4,320,format))
        assertEquals(1,gate.claim(2,320,format)!!.attempt)
    }

    @Test fun firstPromptRequestsOnlyAlternateAndFirstVoiceDoesNotPreclaimMedia(){
        val prompt=AudioDemandGate()
        val ticket=prompt.claim(2,320,format)!!
        assertEquals(100,CarPlayAudioPlan.implementedRouteForCategory(ticket.category).type)
        // No prior empty media request owns the shared type100 slot.
        val voice=prompt.claim(3,320,format)!!
        assertEquals(100,CarPlayAudioPlan.implementedRouteForCategory(voice.category).type)
        assertEquals(1,voice.attempt)
        assertEquals(1,prompt.claim(1,320,format)!!.attempt)
    }

    @Test fun explicitBootstrapAndFirstPcmShareOneSetup():Unit=runBlocking {
        val gate=AudioDemandGate()
        assertNull(gate.claim(2,0,format))
        val ticket=gate.prime(2,format)!!
        assertNull(gate.claim(2,320,format))
        assertTrue(gate.install(ticket,Stream(2)){})
        assertNull(gate.prime(2,format))
        assertNull(gate.claim(2,320,format))
        gate.close()
        assertNull(gate.prime(1,format))
    }

    @Test fun pendingAndInstalledCategoryDoNotRepeatSetup():Unit=runBlocking {
        val gate=AudioDemandGate()
        val ticket=gate.claim(2,320,format)!!
        assertNull(gate.claim(2,320,format))
        val bridge=HostAudioBridge({},configureRoute={})
        try{
            assertEquals(48000,bridge.musicSampleRate())
            assertTrue(gate.install(ticket,Stream(2),bridge::attach))
            assertEquals(setOf(2),bridge.categories())
            repeat(10){assertNull(gate.claim(2,320,format))}
        }finally{gate.close();bridge.close()}
    }

    @Test fun failuresBackOffAndStopAfterTwoAttempts(){
        var clock=0L
        val gate=AudioDemandGate(now={clock},retryDelayNs=100)
        val first=gate.claim(2,320,format)!!
        gate.failed(first)
        repeat(100){assertNull(gate.claim(2,320,format))}
        clock=99;assertNull(gate.claim(2,320,format))
        clock=100
        val second=gate.claim(2,320,format)!!
        assertEquals(2,second.attempt)
        gate.failed(first) // A late failure cannot cancel the newer pending request.
        assertNull(gate.claim(2,320,format))
        gate.failed(second);clock=Long.MAX_VALUE
        assertNull(gate.claim(2,320,format))
    }

    @Test fun lateSetupAfterCloseIsClosedWithoutAttachment():Unit=runBlocking {
        val gate=AudioDemandGate()
        val ticket=gate.claim(2,320,format)!!
        val stream=Stream(2);var attachments=0
        gate.close()
        assertFalse(gate.install(ticket,stream){attachments++})
        assertTrue(stream.closed);assertEquals(0,attachments)
        assertNull(gate.claim(3,320,format))
    }

    @Test fun failedAttachmentReleasesResourceAndAllowsOnlyBoundedRetry():Unit=runBlocking {
        var clock=0L
        val gate=AudioDemandGate(now={clock},retryDelayNs=100)
        val stream=Stream(2)
        val ticket=gate.claim(2,320,format)!!
        try{gate.install(ticket,stream){error("downstream closed")};fail("must fail")}
        catch(_:IllegalStateException){}
        assertTrue(stream.closed)
        assertNull(gate.claim(2,320,format));clock=100
        assertEquals(2,gate.claim(2,320,format)!!.attempt)
    }

    @Test fun closeDuringAttachmentCannotKeepTheReturnedTransportLive():Unit=runBlocking {
        val gate=AudioDemandGate()
        val ticket=gate.claim(2,320,format)!!
        val stream=Stream(2)
        assertFalse(gate.install(ticket,stream){gate.close()})
        assertTrue(stream.closed)
        assertNull(gate.claim(2,320,format))
    }
}
