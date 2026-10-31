package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.sessioncore.TransportErrorCategory
import io.github.iamr0s.carvex.sessioncore.TransportException
/** Old NSD cannot scope discovery to a Network. Do not accept arbitrary global candidates. */
internal object ReceiverDiscoveryScope {
    fun requireSupported(api:Int,hasEndpoint:Boolean) {
        if(api<33&&!hasEndpoint)throw TransportException(TransportErrorCategory.PLATFORM_UNSUPPORTED,
            "Android 10-12 requires a 43/01 receiver endpoint; network-scoped 57/03-only discovery requires Android 13 or later")
    }
}
