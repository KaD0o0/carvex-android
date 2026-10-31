package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class IpLiteralTest {
    @Test fun numericFamiliesAndScopeArePreserved(){
        assertEquals("192.168.2.1",IpLiteral.parse("192.168.2.1").address.hostAddress)
        assertEquals("wlan1",IpLiteral.parse("fe80::1%wlan1").scope)
        assertNull(IpLiteral.parse("2001:db8::1").scope)
    }
    @Test fun malformedNamesAndUnsafeAddressesNeverBecomeDnsCandidates(){
        for(value in listOf("receiver.local","999.1.2.3","192.168.001.1","::::","::","::1","ff02::1", "127.0.0.1",
            "192.168.1.1%wlan1","2001:db8::1%wlan1","fe80::1%","fe80::1%wlan1%wlan2"))
            assertThrows(value,IllegalArgumentException::class.java){IpLiteral.parse(value)}
    }
    @Test fun compatibilityNormalizesEquivalentNumericSpellingsAndRemoteScope(){
        assertEquals("192.168.1.2",IpLiteral.normalizeCompatible(" 192.168.001.002 "))
        assertEquals("192.168.1.2",IpLiteral.normalizeCompatible("::ffff:192.168.1.2"))
        val linkLocal=IpLiteral.normalizeCompatible("[fe80::1%accessory_wifi]")
        assertTrue(IpLiteral.parse(linkLocal).address.isLinkLocalAddress)
        assertNull(IpLiteral.parse(linkLocal).scope)
        assertEquals(IpLiteral.normalizeCompatible("2001:db8::1"),IpLiteral.normalizeCompatible(" [2001:db8::1] "))
    }
    @Test fun compatibilityStillRejectsMissingUnusableAndNonNumericAddresses(){
        for(text in listOf("", "receiver.local", "localhost", "127.000.0.1", "0.0.0.0", "255.300.1.2", "224.0.0.1",
            "[::]", "[::1]", "::ffff:127.0.0.1", "[ff02::1]", "[fe80::1]:7000", "2001:db8::1%other", "fe80::1%x%y"))
            assertThrows(text,IllegalArgumentException::class.java){IpLiteral.normalizeCompatible(text)}
    }
}
