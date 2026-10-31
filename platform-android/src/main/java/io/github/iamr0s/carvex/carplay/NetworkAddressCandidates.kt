package io.github.iamr0s.carvex.carplay

import java.net.InetAddress
import java.net.Inet6Address

/** Inputs have already been scoped to the selected Network. Actual connect determines reachability. */
object NetworkAddressCandidates {
    fun local(addresses:List<InetAddress>):List<InetAddress> = addresses
        .filter{!it.isLoopbackAddress&&!it.isAnyLocalAddress&&!it.isMulticastAddress}
        .distinctBy{it.hostAddress}.take(8)

    /** Choose only from the selected Wi-Fi's addresses, preserving their preference order.
     * A scoped link-local peer needs a link-local source on the same IPv6 interface. */
    fun sourceFor(local:List<InetAddress>,peer:InetAddress):InetAddress? {
        if(peer.isLoopbackAddress||peer.isAnyLocalAddress||peer.isMulticastAddress)return null
        return local.asSequence()
            .filter{!it.isLoopbackAddress&&!it.isAnyLocalAddress&&!it.isMulticastAddress}
            .firstOrNull{source->
                when{
                    peer is Inet6Address && source is Inet6Address->
                        if(peer.isLinkLocalAddress)source.isLinkLocalAddress&&source.scopeId>0&&
                            (peer.scopeId==0||source.scopeId==peer.scopeId)
                        else !source.isLinkLocalAddress
                    peer !is Inet6Address && source !is Inet6Address->true
                    else->false
                }
            }
    }

    fun peers(local:List<InetAddress>,direct:List<InetAddress>,discovered:List<InetAddress>):List<InetAddress> =
        (direct+discovered).filter{candidate->
            !candidate.isLoopbackAddress&&!candidate.isAnyLocalAddress&&!candidate.isMulticastAddress&&
                local.any{(it is Inet6Address)==(candidate is Inet6Address)}
        }.distinctBy{it.hostAddress}.take(8)
}
