package io.github.iamr0s.carvex.carplay

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class NetworkAddressCandidatesTest {
    private fun scopedV6(address:String,scope:Int)=Inet6Address.getByAddress(null,
        InetAddress.getByName(address).address,scope)

    @Test fun anUnusableFirstAddressFamilyDoesNotBlockLaterDiscoveryCandidates(){
        val local=InetAddress.getByName("192.168.43.10")
        val ipv6=InetAddress.getByName("fe80::1")
        val ipv4=InetAddress.getByName("192.168.43.1")
        assertEquals(listOf(ipv4),NetworkAddressCandidates.peers(listOf(local),listOf(ipv6),listOf(ipv4)))
    }
    @Test fun candidatesRemainBoundedDistinctAndKeepBothAvailableFamilies(){
        val ipv6=InetAddress.getByName("fe80::1")
        val ipv4=InetAddress.getByName("192.168.43.1")
        val loopback=InetAddress.getByName("127.0.0.1")
        val wildcard=InetAddress.getByName("0.0.0.0")
        val local=NetworkAddressCandidates.local(listOf(ipv4,ipv6,loopback,wildcard))
        assertEquals(listOf(ipv4,ipv6),local)
        assertEquals(listOf(ipv6,ipv4),NetworkAddressCandidates.peers(local,listOf(ipv6),listOf(ipv4,ipv6,loopback,wildcard)))
        val many=(1..20).map{InetAddress.getByName("192.168.43.$it")}
        assertEquals(8,NetworkAddressCandidates.peers(local,many,emptyList()).size)
    }

    @Test fun sourceSelectionPreservesIpv4OrderAndNeverCrossesAddressFamilies(){
        val first=InetAddress.getByName("192.168.43.10")
        val second=InetAddress.getByName("192.168.43.11")
        val ipv6=InetAddress.getByName("2001:db8::10")
        val peer=InetAddress.getByName("192.168.43.1")
        assertEquals(first,NetworkAddressCandidates.sourceFor(listOf(ipv6,first,second),peer))
        assertNull(NetworkAddressCandidates.sourceFor(listOf(ipv6),peer))
        assertNull(NetworkAddressCandidates.sourceFor(listOf(first),InetAddress.getByName("2001:db8::1")))
        assertNull(NetworkAddressCandidates.sourceFor(emptyList(),peer))
        val ipv4Only=(10..18).map{InetAddress.getByName("192.168.43.$it")}
        assertEquals(ipv6,NetworkAddressCandidates.sourceFor(ipv4Only+ipv6,InetAddress.getByName("2001:db8::1")))
    }

    @Test fun linkLocalIpv6RequiresAScopedLinkLocalSourceOnThePeersInterface(){
        val global=InetAddress.getByName("2001:db8::10")
        val unscoped=InetAddress.getByName("fe80::10")
        val otherInterface=scopedV6("fe80::11",8)
        val selected=scopedV6("fe80::12",7)
        val peer=scopedV6("fe80::1",7)
        assertEquals(selected,NetworkAddressCandidates.sourceFor(listOf(global,unscoped,otherInterface,selected),peer))
        assertNull(NetworkAddressCandidates.sourceFor(listOf(global,unscoped,otherInterface),peer))
        assertEquals(otherInterface,NetworkAddressCandidates.sourceFor(
            listOf(global,unscoped,otherInterface,selected),InetAddress.getByName("fe80::1")))
    }

    @Test fun nonLinkLocalIpv6CannotUseALinkLocalSource(){
        val link=scopedV6("fe80::10",7)
        val uniqueLocal=InetAddress.getByName("fd00::10")
        val global=InetAddress.getByName("2001:db8::10")
        val peer=InetAddress.getByName("2001:db8::1")
        assertNull(NetworkAddressCandidates.sourceFor(listOf(link),peer))
        assertEquals(uniqueLocal,NetworkAddressCandidates.sourceFor(listOf(link,uniqueLocal,global),peer))
    }

    @Test fun unusableLocalAddressesAndUnusablePeersAreNeverSelected(){
        val ipv4=InetAddress.getByName("192.168.43.10")
        val invalid4=listOf("127.0.0.1","0.0.0.0","224.0.0.1").map{InetAddress.getByName(it)}
        assertEquals(ipv4,NetworkAddressCandidates.sourceFor(invalid4+ipv4,InetAddress.getByName("192.168.43.1")))
        val ipv6=InetAddress.getByName("2001:db8::10")
        val invalid6=listOf("::1","::","ff02::1").map{InetAddress.getByName(it)}
        assertEquals(ipv6,NetworkAddressCandidates.sourceFor(invalid6+ipv6,InetAddress.getByName("2001:db8::1")))
        for(peer in invalid4+invalid6)assertNull(NetworkAddressCandidates.sourceFor(listOf(ipv4,ipv6),peer))
    }
}
