package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/** 候选必须与本端同族；车机只宣告 IPv6 link-local 时，本端没有 IPv6 源就没有可试的地址。
 *  现场（2026-10-27，DiPlay）正是这条：本地只有 192.168.237.249，对端只有 fe80::…，候选为空。 */
class NetworkAddressCandidatesTest {
    private fun v4(text:String)=InetAddress.getByName(text)
    private fun linkLocal(scope:Int):Inet6Address =
        Inet6Address.getByAddress(null, InetAddress.getByName("fe80::6c6e:66ff:fedb:89f0").address, scope)

    @Test fun aLinkLocalPeerNeedsAnIPv6SourceOnTheSameInterface(){
        val peer=linkLocal(24)
        assertTrue("只有 IPv4 源时不能拿它去连 IPv6 对端",
            NetworkAddressCandidates.peers(listOf(v4("192.168.237.249")),emptyList(),listOf(peer)).isEmpty())
        val dual=listOf(v4("192.168.237.249"),linkLocal(24))
        assertEquals(listOf(peer),NetworkAddressCandidates.peers(dual,emptyList(),listOf(peer)))
    }

    @Test fun theDeclaredEndpointIsTriedBeforeTheDiscoveredOne(){
        val declared=linkLocal(24)
        val discovered=v4("192.168.237.1")
        val dual=listOf(v4("192.168.237.249"),linkLocal(24))
        assertEquals(listOf(declared,discovered),
            NetworkAddressCandidates.peers(dual,listOf(declared),listOf(discovered)))
    }

    @Test fun aLinkLocalPeerNeedsAScopedSource(){
        val peer=linkLocal(24)
        assertNull("没有 scope 的 link-local 源连不出去",
            NetworkAddressCandidates.sourceFor(listOf(linkLocal(0)),peer))
        assertEquals(linkLocal(24),NetworkAddressCandidates.sourceFor(listOf(linkLocal(24)),peer))
    }

    @Test fun loopbackAndMulticastAreNeverCandidates(){
        val local=listOf(v4("127.0.0.1"),v4("0.0.0.0"),v4("224.0.0.251"))
        assertTrue(NetworkAddressCandidates.local(local).isEmpty())
    }
}
