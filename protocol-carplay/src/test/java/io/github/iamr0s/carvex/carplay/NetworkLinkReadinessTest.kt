package io.github.iamr0s.carvex.carplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NetworkLinkReadinessTest {
    private fun address(value: String) = InetAddress.getByName(value)
    @Test fun interfaceAndLocalAddressAreBothRequired() {
        for (name in listOf(null, "", " ")) assertFalse(NetworkLinkReadiness.isReady(name,listOf(address("192.0.2.2"))))
        assertFalse(NetworkLinkReadiness.isReady("wlan0",emptyList()))
        assertTrue(NetworkLinkReadiness.isReady("wlan0",listOf(address("192.0.2.2"))))
    }
    @Test fun nonSourceAddressesCannotMakeTheNetworkReady() {
        for (value in listOf("0.0.0.0", "::", "127.0.0.1", "::1", "224.0.0.1", "ff02::1"))
            assertFalse(value,NetworkLinkReadiness.isReady("wlan0",listOf(address(value))))
    }
    @Test fun linkLocalAddressIsNotConfusedWithPeerReachabilityOrAnInternetRequirement() {
        for (value in listOf("169.254.1.2", "fe80::2", "192.0.2.2", "2001:db8::2"))
            assertTrue(value,NetworkLinkReadiness.isReady("wlan0",listOf(address(value))))
    }
}
