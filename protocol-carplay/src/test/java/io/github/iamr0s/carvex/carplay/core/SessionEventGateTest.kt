package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

class SessionEventGateTest {
    private val binding=SecurityBinding(1,"peer",2)
    private fun wire(first:String,seq:Int,body:ByteArray)=ByteArrayOutputStream().also{
        RtspWire.write(it,first,mapOf("CSeq" to seq.toString()),body)
    }.toByteArray()
    @Test fun commandsInterleavedBeforeM2ReceiveRepliesWithoutHostCallbacks(){
        val gate=SessionEventGate(binding){true}
        val bytes=ByteArrayOutputStream()
        for((index,type) in listOf("requestUI","setNightMode","requestSiri").withIndex())
            bytes.write(wire("POST /command RTSP/1.0",90+index,Plist.encode(mapOf("type" to type))))
        bytes.write(wire("RTSP/1.0 200 OK",1,Tlv8.encode(Tlv8.state(2),7 to byteArrayOf(3),8 to byteArrayOf(60))))
        var callbacks=0
        val output=ByteArrayOutputStream()
        val client=RtspClient(ByteArrayInputStream(bytes.toByteArray()),output){gate.dispatch{callbacks++;200 to byteArrayOf()}}
        val error=assertThrows(SecurityFailure::class.java){HomeKit(client,PairIdentity("controller",ByteArray(32))).setup(setupCode=byteArrayOf(1))}
        assertEquals(SecurityReason.PAIR_BACKOFF,error.diagnostic.localReason)
        assertEquals(0,callbacks)
        val sent=ByteArrayInputStream(output.toByteArray())
        assertEquals("POST /pair-setup RTSP/1.0",RtspWire.read(sent).first)
        for(seq in 90..92){val reply=RtspWire.read(sent);assertEquals(403,reply.status);assertEquals(seq.toString(),reply.headers["cseq"])}
        assertEquals(0,sent.available())
    }
    @Test fun verifiedNegotiatedSessionHandlesInterleavedRequestsButRevokedContextDoesNot(){
        val live=AtomicBoolean(true)
        val gate=SessionEventGate(binding){live.get()}
        var callbacks=0
        fun dispatch()=gate.dispatch{callbacks++;200 to byteArrayOf()}.first
        assertEquals(403,dispatch())
        gate.authenticated(binding)
        assertEquals(503,dispatch())
        gate.negotiated(binding)
        assertEquals(200,dispatch())
        live.set(false)
        assertEquals(403,dispatch())
        assertEquals(SecurityReason.STALE_CONTEXT,assertThrows(SecurityFailure::class.java){gate.requireCurrent()}.diagnostic.localReason)
        assertEquals(1,callbacks)
    }
    @Test fun wrongEpochAndClosedSessionCannotReopenBusinessCallbacks(){
        val gate=SessionEventGate(binding){true}
        assertThrows(SecurityFailure::class.java){gate.authenticated(binding.copy(epoch=3))}
        assertThrows(SecurityFailure::class.java){gate.negotiated(binding)}
        gate.authenticated(binding);gate.negotiated(binding);gate.close()
        assertThrows(SecurityFailure::class.java){gate.authenticated(binding)}
        assertEquals(403,gate.dispatch{fail("closed session callback");200 to byteArrayOf()}.first)
    }
}
