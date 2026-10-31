package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverFeatureEvidenceTest {
    private val binding = SecurityBinding(4, "synthetic-peer", 9)
    private fun inputs(metadata: ReceiverMetadata): SecurityInputs =
        WifiBootstrap("test", "password1", 2, null,
            authentication = BootstrapAuthentication(4, 9, 2, true))
            .resolved("192.0.2.1", 7000, "02:11:22:33:44:55", null, metadata)
            .securityInputs(binding, metadata, true, true, pairedCapabilityGate = true)

    @Test fun legacyMetadataConstructionRemainsCompatible() {
        val metadata = ReceiverMetadata("pair-id", null, 1UL shl 38)
        assertEquals(AirPlayFeatureSource.LEGACY_FLAGS, metadata.effectiveFeatureEvidence.source)
        assertEquals(1UL shl 38, inputs(metadata).features)
        assertEquals(true, inputs(metadata).featureEvidence!!.has(38))
    }

    @Test fun fullExtendedEvidenceSurvivesMetadataAndBootstrapWithoutTruncation() {
        val evidence = AirPlayFeatureEvidence(AirPlayFeatureSource.BONJOUR_FEX,
            AirPlayFeatureSet(ByteArray(11).apply { this[10] = 1 }))
        val metadata = ReceiverMetadata("pair-id", null, 1UL shl 38, featureEvidence = evidence)
        val input = inputs(metadata)
        assertEquals(0UL, input.features)
        assertSame(evidence, input.featureEvidence)
        assertEquals(true, input.featureEvidence!!.has(80))
        assertEquals(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED,
            assertThrows(SecurityFailure::class.java) { SecurityRouter.select(input) }.diagnostic.localReason)
    }

    @Test fun malformedExtendedEvidenceDoesNotDisappearIntoLegacyOrMissing() {
        val evidence = AirPlayFeatureEvidence.fromBonjour("!", "0x4000000000")
        val metadata = ReceiverMetadata("pair-id", null, 1UL shl 38, featureEvidence = evidence)
        assertNull(metadata.effectiveFeatures)
        val input = inputs(metadata)
        assertNull(input.features)
        assertSame(evidence, input.featureEvidence)
        assertEquals(SecurityReason.SECURITY_INPUT_MALFORMED,
            assertThrows(SecurityFailure::class.java) { SecurityRouter.select(input) }.diagnostic.localReason)
    }
}
