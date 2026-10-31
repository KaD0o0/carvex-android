package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class InvitationIdentityTest {
    @Test fun decimalHexAndHardwareAddressResolveSameKnownIdentity(){
        val expected="aabbccddeeff"
        assertTrue(expected in ReceiverIdentity.invitationCandidates(0xaabbccddeeffL.toString()))
        assertTrue(expected in ReceiverIdentity.invitationCandidates("AABBCCDDEEFF"))
        assertTrue(expected in ReceiverIdentity.invitationCandidates("AA:BB:CC:DD:EE:FF"))
        assertFalse(expected in ReceiverIdentity.invitationCandidates("AABBCCDDEE00"))
    }
    @Test fun numericAmbiguityIsResolvedOnlyAgainstKnownPeer(){
        val values=ReceiverIdentity.invitationCandidates("123456789012")
        assertTrue("123456789012" in values)
        assertTrue(123456789012L.toString(16).padStart(12,'0') in values)
    }
    @Test fun invalidAndOver48BitValuesRejected(){
        for(value in listOf(null,"","-1","281474976710656","0xAABBCCDDEEFF","550e8400-e29b-41d4-a716-446655440000","AA:BB:CC:DD:EE:GG"))assertTrue(ReceiverIdentity.invitationCandidates(value).isEmpty())
    }
}
