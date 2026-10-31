package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionModeTest {
    @Test fun displayNameContainsProtocolAndCarrier() {
        assertEquals("CarPlay-Bluetooth", ConnectionMode.CARPLAY_BLUETOOTH.displayName)
        assertEquals("CarPlay-WIFI", ConnectionMode.CARPLAY_WIFI.displayName)
        assertEquals("CarPlay-USB", ConnectionMode.CARPLAY_USB.displayName)
    }

    @Test fun rawCarrierNamesAreNotAcceptedAsProtocolModes() {
        assertEquals(null, ConnectionMode.parse("Bluetooth"))
        assertEquals(null, ConnectionMode.parse("Wi-Fi"))
        assertEquals(null, ConnectionMode.parse("USB"))
    }

    @Test fun modeIdentityIncludesProtocol() {
        assertTrue(ConnectionMode.CARPLAY_BLUETOOTH.id.startsWith("carplay-"))
        assertFalse(ConnectionMode.CARPLAY_BLUETOOTH.id == CarrierKind.BLUETOOTH.id)
    }
}
