package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AirPlaySecurityCapabilitiesTest {
    private fun flags(vararg bits: Int) = bits.fold(0UL) { mask, bit -> mask or (1UL shl bit) }

    @Test fun missingMaskStaysUnknownAndDoesNotAuthorizeAnything() {
        val c = AirPlaySecurityCapabilities(null)
        assertNull(c.coreUtilsPairingAndEncryption)
        assertNull(c.directCoreUtilsPairing)
        assertNull(c.hkPairingAndAccessControl)
        assertNull(c.systemPairing)
        assertNull(c.transientPairing)
        assertNull(c.legacyPairing)
        assertNull(c.unifiedPairingAndMfi)
        assertFalse(c.requiresUnsupportedCuEntry)
    }

    @Test fun eachOfTheFourOriginalCuTermsIsSufficient() {
        for (bit in listOf(38, 46, 43, 48)) {
            val c = AirPlaySecurityCapabilities(flags(bit))
            assertEquals("bit=$bit", true, c.coreUtilsPairingAndEncryption)
            assertEquals(bit == 38, c.directCoreUtilsPairing)
            assertEquals(bit != 38, c.requiresUnsupportedCuEntry)
        }
    }

    @Test fun allSixteenCuCombinationsPreserveTheOrAndDirectEntryDistinction() {
        val bits = listOf(38, 46, 43, 48)
        for (combination in 0..15) {
            val enabled = bits.filterIndexed { index, _ -> combination and (1 shl index) != 0 }
            val c = AirPlaySecurityCapabilities(flags(*enabled.toIntArray()))
            assertEquals(combination != 0, c.coreUtilsPairingAndEncryption)
            assertEquals(combination != 0 && combination and 1 == 0, c.requiresUnsupportedCuEntry)
        }
    }

    @Test fun legacyAndUnifiedMfiAreSeparateFromCuSupport() {
        val c = AirPlaySecurityCapabilities(flags(27, 51))
        assertEquals(true, c.legacyPairing)
        assertEquals(true, c.unifiedPairingAndMfi)
        assertEquals(false, c.coreUtilsPairingAndEncryption)
        assertFalse(c.requiresUnsupportedCuEntry)
    }

    @Test fun unrelatedAndHighBitsDoNotAliasCuSupport() {
        for (bit in 0..63) {
            if (bit in listOf(38, 46, 43, 48)) continue
            assertEquals("bit=$bit", false, AirPlaySecurityCapabilities(flags(bit)).coreUtilsPairingAndEncryption)
        }
    }

    @Test fun knownZeroIsNotMissing() {
        val c = AirPlaySecurityCapabilities(0UL)
        assertEquals(false, c.coreUtilsPairingAndEncryption)
        assertEquals(false, c.legacyPairing)
        assertEquals(false, c.unifiedPairingAndMfi)
        assertNotEquals(c.diagnostic(), AirPlaySecurityCapabilities(null).diagnostic())
    }
}
