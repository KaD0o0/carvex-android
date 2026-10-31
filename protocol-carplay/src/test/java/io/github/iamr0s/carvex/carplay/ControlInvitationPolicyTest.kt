package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.RtspMessage
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ControlInvitationPolicyTest {
    private fun ip(text:String)=InetAddress.getByName(text)
    private val local=listOf(ControlInvitationPolicy.Local(ip("192.0.2.10"),24))
    private fun request(id:String="123",line:String="GET /ctrl-int/1/connect HTTP/1.1",body:ByteArray=byteArrayOf())=
        RtspMessage(line,mapOf("airplay-receiver-device-id" to id),body)
    private fun accept(message:RtspMessage=request(),peer:String="192.0.2.1",known:Set<InetAddress> = emptySet())=
        ControlInvitationPolicy.receiver(message,ip(peer),local,known)

    @Test fun decimalU64IncludingMaximumIsParsedWithoutTrustPromotion() {
        assertEquals(123UL,accept());assertEquals(0UL,accept(request("0")))
        assertEquals(ULong.MAX_VALUE,accept(request("18446744073709551615")))
    }
    @Test fun malformedIdIsNotJustHeaderPresence() {
        for(value in listOf("","-1","+1","1.0","0x10"," 1","1 ","1x","１２３",
            "18446744073709551616","000000000000000000001"))assertNull(value,accept(request(value)))
        assertNull(accept(RtspMessage("GET /ctrl-int/1/connect HTTP/1.1",emptyMap(),byteArrayOf())))
    }
    @Test fun exactMethodPathVersionAndEmptyBodyAreRequired() {
        for(line in listOf("POST /ctrl-int/1/connect HTTP/1.1","GET /ctrl-int/1/connect?x HTTP/1.1",
            "GET /ctrl-int/1/connect HTTP/2","GET /other HTTP/1.1","GET  /ctrl-int/1/connect HTTP/1.1"))
            assertNull(line,accept(request(line=line)))
        assertNull(accept(request(body=byteArrayOf(1))))
        assertEquals(123UL,accept(request(line="GET /ctrl-int/1/connect RTSP/1.0")))
        assertEquals(123UL,accept(request(line="GET /ctrl-int/1/connect HTTP/1.0")))
    }
    @Test fun unknownEndpointIsRestrictedToSelectedOnLinkNetwork() {
        assertNull(accept(peer="198.51.100.1"));assertNull(accept(peer="2001:db8::1"))
        assertNull(ControlInvitationPolicy.receiver(request(),ip("192.0.2.1"),emptyList(),emptySet()))
    }
    @Test fun selfLoopbackUnspecifiedAndMulticastAreRejectedEvenIfKnown() {
        for(peer in listOf("192.0.2.10","127.0.0.1","0.0.0.0","224.0.0.1","::1","::","ff02::1"))
            assertNull(peer,accept(peer=peer,known=setOf(ip(peer))))
    }
    @Test fun knownCandidatesRequireExactAddressRatherThanAnySubnetPeer() {
        assertEquals(123UL,accept(known=setOf(ip("192.0.2.1"))))
        assertNull(accept(peer="192.0.2.2",known=setOf(ip("192.0.2.1"))))
    }
    @Test fun ipv6AndNonOctetPrefixesAreCheckedBitwise() {
        val ipv6=listOf(ControlInvitationPolicy.Local(ip("2001:db8:1::10"),64))
        assertEquals(123UL,ControlInvitationPolicy.receiver(request(),ip("2001:db8:1::1"),ipv6,emptySet()))
        assertNull(ControlInvitationPolicy.receiver(request(),ip("2001:db8:2::1"),ipv6,emptySet()))
        val subnet=listOf(ControlInvitationPolicy.Local(ip("192.0.2.130"),25))
        assertEquals(123UL,ControlInvitationPolicy.receiver(request(),ip("192.0.2.200"),subnet,emptySet()))
        assertNull(ControlInvitationPolicy.receiver(request(),ip("192.0.2.1"),subnet,emptySet()))
    }
    @Test fun invalidOrDefaultRoutePrefixDoesNotAuthorizeArbitraryPeers() {
        for(prefix in listOf(-1,0,33))assertNull(ControlInvitationPolicy.receiver(request(),ip("192.0.2.1"),
            listOf(ControlInvitationPolicy.Local(ip("192.0.2.10"),prefix)),emptySet()))
    }
}
