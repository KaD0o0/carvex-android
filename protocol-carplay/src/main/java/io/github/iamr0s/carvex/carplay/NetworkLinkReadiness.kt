package io.github.iamr0s.carvex.carplay

import java.net.InetAddress

/** Local Android precondition shared by reuse and new-network callbacks.
 * This proves only an interface and usable local address exist. It does not
 * claim peer reachability, DNS discovery, authentication, or Apple policy.
 */
internal object NetworkLinkReadiness {
    fun isReady(interfaceName: String?, addresses: List<InetAddress>): Boolean =
        !interfaceName.isNullOrBlank() && addresses.any {
            !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress
        }
}
