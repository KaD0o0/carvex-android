package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ControlEndpointCandidatesTest {
    private fun v4(last:Int)=InetAddress.getByAddress(byteArrayOf(192.toByte(),0,2,last.toByte()))
    private val local=listOf(v4(10))
    @Test fun differentSourcesNeverMakeAnAddressPortCrossProduct(){
        val result=ControlEndpointCandidates.select(local,listOf(v4(1)),5000,listOf(v4(2)),7000)
        assertEquals(listOf(v4(1) to 5000,v4(2) to 7000),result.map{it.address to it.port})
        assertEquals(listOf(ControlEndpointCandidates.Source.BOOTSTRAP,ControlEndpointCandidates.Source.BONJOUR),result.map{it.source})
    }
    @Test fun sameAddressDifferentPortsRemainDistinctButExactTuplesDeduplicate(){
        assertEquals(listOf(5000,7000),ControlEndpointCandidates.select(local,listOf(v4(1)),5000,listOf(v4(1)),7000).map{it.port})
        assertEquals(1,ControlEndpointCandidates.select(local,listOf(v4(1),v4(1)),5000,listOf(v4(1)),5000).size)
    }
    @Test fun missingDiscoveredPortNeverBorrowsBootstrapPort(){
        assertEquals(listOf(v4(1)),ControlEndpointCandidates.select(local,listOf(v4(1)),5000,listOf(v4(2)),null).map{it.address})
    }
    @Test fun invalidSourcePortDoesNotPoisonTheOtherSource(){
        assertEquals(listOf(v4(2)),ControlEndpointCandidates.select(local,listOf(v4(1)),0,listOf(v4(2)),7000).map{it.address})
        assertEquals(listOf(v4(1)),ControlEndpointCandidates.select(local,listOf(v4(1)),5000,listOf(v4(2)),65536).map{it.address})
    }
    @Test fun familyReadinessFilterStillAppliesToBothSources(){
        val peer6=InetAddress.getByAddress(ByteArray(16).apply{this[0]=0x20;this[1]=1;this[15]=1})
        assertTrue(ControlEndpointCandidates.select(local,listOf(peer6),5000,listOf(peer6),7000).isEmpty())
        assertEquals(2,ControlEndpointCandidates.select(listOf(peer6),listOf(peer6),5000,listOf(peer6),7000).size)
    }
}
