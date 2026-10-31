package io.github.iamr0s.carvex.carplay

import java.net.InetAddress

/** Local selection policy, not an asserted Apple priority order. Each address
 * stays paired with the port supplied by that same source; Bonjour's SRV port
 * must not silently replace the port on a different 4301 address. Identity and
 * authentication remain separate gates. Socket connect proves reachability. */
internal object ControlEndpointCandidates {
    enum class Source { BOOTSTRAP, BONJOUR }
    data class Endpoint(val address:InetAddress,val port:Int,val source:Source)
    fun select(local:List<InetAddress>,bootstrapAddresses:List<InetAddress>,bootstrapPort:Int,
               discoveredAddresses:List<InetAddress>,discoveredPort:Int?):List<Endpoint> {
        fun source(addresses:List<InetAddress>,port:Int?,kind:Source):List<Endpoint> {
            if(port==null || port !in 1..65535)return emptyList()
            return NetworkAddressCandidates.peers(local,addresses,emptyList()).map{Endpoint(it,port,kind)}
        }
        return (source(bootstrapAddresses,bootstrapPort,Source.BOOTSTRAP)+
            source(discoveredAddresses,discoveredPort,Source.BONJOUR))
            .distinctBy{it.address.hostAddress to it.port}.take(16)
    }
}
