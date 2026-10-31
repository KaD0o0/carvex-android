package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.sessioncore.ConnectionMode
import io.github.iamr0s.carvex.sessioncore.PeerEndpoint
import io.github.iamr0s.carvex.sessioncore.ProtocolKind
import io.github.iamr0s.carvex.sessioncore.ProtocolSession
import io.github.iamr0s.carvex.sessioncore.TransportAdapter

/**
 * CarPlay's bootstrap result is a protocol decision, not a synonym for Wi-Fi.
 * A receiver can complete identification and select a wired path without ever
 * sending a Wi-Fi configuration message.  Keeping this result explicit lets a
 * USB adapter be added without changing iAP2 negotiation code.
 */
sealed interface CarPlayBootstrapPath {
    val mode: ConnectionMode

    data class Wireless(val config: WifiBootstrap) : CarPlayBootstrapPath {
        override val mode: ConnectionMode = ConnectionMode.CARPLAY_WIFI
    }

    data object Wired : CarPlayBootstrapPath {
        override val mode: ConnectionMode = ConnectionMode.CARPLAY_USB
    }
}

object CarPlayConnectionModes {
    val bluetooth: ConnectionMode = ConnectionMode.CARPLAY_BLUETOOTH
    val wifi: ConnectionMode = ConnectionMode.CARPLAY_WIFI
    val usb: ConnectionMode = ConnectionMode.CARPLAY_USB
}

/** Registry used by the session layer; concrete Android adapters are injected. */
class CarPlayTransportRegistry(
    adapters: Iterable<TransportAdapter>,
) {
    private val byCarrier = adapters
        .filter { it.mode.protocol == ProtocolKind.CARPLAY }
        .associateBy { it.mode.carrier }

    fun supports(mode: ConnectionMode): Boolean =
        mode.protocol == ProtocolKind.CARPLAY && byCarrier.containsKey(mode.carrier)

    fun adapter(mode: ConnectionMode): TransportAdapter? =
        if (mode.protocol == ProtocolKind.CARPLAY) byCarrier[mode.carrier] else null
}

/** Protocol session factory deliberately accepts a transport-neutral endpoint. */
fun interface CarPlaySessionFactory {
    fun create(mode: ConnectionMode, endpoint: PeerEndpoint): ProtocolSession
}
