package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.assertEquals
import org.junit.Test
class VideoDeliveryGateTest {
    @Test fun keyCannotStartBeforeConfigurationAndConfigChangeRequiresAnotherKey(){
        val gate=VideoDeliveryGate()
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,true,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(true,false,0))
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,true,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(true,false,0))
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,false,0))
        gate.reset()
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,true,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(true,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,true,0))
    }
    @Test fun staleReferenceDropsDependentsUntilNextKey(){
        val gate=VideoDeliveryGate(150)
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(true,false,500))
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,true,500))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,false,150))
        assertEquals(VideoDeliveryGate.Decision.DROP_REQUEST_KEY,gate.decide(false,false,151))
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(true,false,0))
        assertEquals(VideoDeliveryGate.Decision.DROP,gate.decide(false,false,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,true,0))
        assertEquals(VideoDeliveryGate.Decision.SEND,gate.decide(false,false,0))
    }
}
