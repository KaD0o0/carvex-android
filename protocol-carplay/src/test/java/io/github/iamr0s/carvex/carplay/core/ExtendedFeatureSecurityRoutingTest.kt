package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class ExtendedFeatureSecurityRoutingTest {
    private val binding = SecurityBinding(8, "synthetic-peer", 3)
    private fun evidence(vararg bits: Int): AirPlayFeatureEvidence {
        val bytes = ByteArray(11)
        bits.forEach { bytes[it / 8] = (bytes[it / 8].toInt() or (1 shl (it % 8))).toByte() }
        return AirPlayFeatureEvidence(AirPlayFeatureSource.BONJOUR_FEX, AirPlayFeatureSet(bytes))
    }
    private fun input(evidence: AirPlayFeatureEvidence) = SecurityInputs(binding,
        endpointSource = SecurityEntry.DISCOVERY, features = 1UL shl 38,
        featureEvidence = evidence, pairedCapabilityGate = true,
        record = PairRecordStatus.ACTIVE, setupProviderBound = true)
    private fun reason(input: SecurityInputs) = assertThrows(SecurityFailure::class.java) {
        SecurityRouter.select(input)
    }.diagnostic.localReason

    @Test fun highAuthenticationBitIsVisibleAndNeverSelectsAnUnimplementedExchange() {
        for (bits in listOf(intArrayOf(80), intArrayOf(80, 38), intArrayOf(80, 51), intArrayOf(80, 26, 14, 23, 31))) {
            val evidence = evidence(*bits)
            val capabilities = AirPlaySecurityCapabilities(0UL, evidence)
            assertEquals(true, capabilities.mutualMfiAuthentication)
            assertEquals(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED, reason(input(evidence)))
        }
    }

    @Test fun extendedInputOverridesEvenAConflictingCompatibilityMask() {
        assertEquals(SecurityRoute.SAP, SecurityRouter.route(input(evidence())))
        assertEquals(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED, reason(input(evidence(46))))
        assertEquals(SecurityAction.PAIR_VERIFY, SecurityRouter.select(input(evidence(38)).copy(features = null)))
    }

    @Test fun malformedAndExplicitMissingEvidenceNeverUseTheFallbackMask() {
        val malformed = AirPlayFeatureEvidence.fromBonjour("!", "0x4000000000")
        assertEquals(SecurityReason.SECURITY_INPUT_MALFORMED, reason(input(malformed)))
        assertEquals(SecurityReason.SECURITY_INPUT_MISSING, reason(input(AirPlayFeatureEvidence.fromLegacyFlags(null))))
        val capabilities = AirPlaySecurityCapabilities(ULong.MAX_VALUE, malformed)
        assertNull(capabilities.directCoreUtilsPairing)
        assertNull(capabilities.coreUtilsPairingAndEncryption)
        assertNull(capabilities.mutualMfiAuthentication)
    }

    @Test fun discoveryEvidenceDoesNotOverrideBindingContinuationOrBootstrapPrecedence() {
        val malformed = input(AirPlayFeatureEvidence.fromBonjour("!", null))
        assertEquals(SecurityReason.STALE_CONTEXT, reason(malformed.copy(expectedBinding = binding.copy(epoch = 2))))
        assertEquals(SecurityReason.PEER_BINDING_CONFLICT, reason(malformed.copy(identityConflict = true)))
        assertEquals(SecurityReason.CONTROL_SCHEMA_UNSUPPORTED, reason(malformed.copy(controlVersion = 3)))
        assertEquals(SecurityReason.ENDPOINT_SOURCE_UNSUPPORTED, reason(malformed.copy(endpointSource = SecurityEntry.UNKNOWN)))
        assertEquals(SecurityAction.CONTINUE_VERIFY, SecurityRouter.select(malformed.copy(verifyContext = binding)))
        assertEquals(SecurityAction.WAIT_START_PARAMETERS, SecurityRouter.select(malformed.copy(endpointSource = SecurityEntry.IAP_START)))
        assertEquals(SecurityReason.SECURITY_INPUT_MISSING, reason(malformed.copy(startReady = true)))
        val ready = malformed.copy(startReady = true, bootstrapAuthenticationAccepted = true,
            networkReady = true, endpointValidated = true)
        assertEquals(SecurityReason.SECURITY_INPUT_MALFORMED, reason(ready))
        assertEquals(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED, reason(ready.copy(featureEvidence = evidence(80, 38))))
        assertEquals(SecurityAction.PAIR_VERIFY, SecurityRouter.select(ready.copy(featureEvidence = null)))
        assertEquals(SecurityAction.CONTINUE_VERIFY, SecurityRouter.select(ready.copy(verifyContext = binding)))
    }
}
