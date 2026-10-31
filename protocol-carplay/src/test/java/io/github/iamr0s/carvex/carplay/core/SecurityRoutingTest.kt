package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class SecurityRoutingTest {
    private val binding = SecurityBinding(3, "synthetic-peer", 9)
    @Test fun allTwentyEightPublishedE18Cases() {
        val cases = Properties().apply { load(SecurityRoutingTest::class.java.getResourceAsStream("/e18-security-routing.properties")) }
        val names = cases.stringPropertyNames().filter { it.endsWith(".expected") }.map { it.removeSuffix(".expected") }
        assertEquals(28, names.size)
        names.forEach { name ->
            fun value(key: String) = cases.getProperty("$name.$key")
            fun flag(key: String, default: Boolean = false) = value(key)?.toBoolean() ?: default
            val i = SecurityInputs(binding,
                expectedBinding = if (flag("staleGeneration")) binding.copy(generation = 2) else binding,
                verifyContext = binding.takeIf { flag("liveVerifyContext") },
                identityConflict = flag("identityConflict"), controlVersion = value("controlVersion")?.toInt() ?: 2,
                bootstrapTransport = SecurityTransport.valueOf(value("bootstrapTransport") ?: "BLUETOOTH_WIFI"),
                endpointSource = SecurityEntry.valueOf(value("endpointSource") ?: value("entry") ?: "IAP_START"),
                startReady = flag("startReady"), bootstrapAuthenticationAccepted = true, networkReady = true, endpointValidated = true,
                features = value("feature38")?.let { if (it.toBoolean()) 1UL shl 38 else 0UL },
                pairedCapabilityGate = value("compatibilityGate")?.toBoolean(), peerHintPresent = flag("peerHintPresent", true),
                record = PairRecordStatus.valueOf(value("record") ?: "ABSENT"), setupProviderBound = flag("setupProviderBound", true))
            val actual = try {
                when (SecurityRouter.select(i)) {
                    SecurityAction.CONTINUE_VERIFY -> "PAIRED_CONTINUE_CURRENT_TRANSACTION"
                    SecurityAction.AUTH_SETUP -> "AUTH_SETUP"
                    else -> SecurityRouter.select(i).name
                }
            } catch (e: SecurityFailure) { e.diagnostic.localReason.name }
            assertEquals(name, value("expected"), actual)
        }
    }
    @Test fun currentVerifyDoesNotMeanAnyStoredPairingRecord() {
        val i = SecurityInputs(binding, record = PairRecordStatus.ACTIVE)
        assertEquals(SecurityAction.WAIT_START_PARAMETERS, SecurityRouter.select(i))
        val failure = assertThrows(SecurityFailure::class.java) {
            SecurityRouter.select(i.copy(verifyContext = binding.copy(epoch = 8)))
        }
        assertEquals(SecurityReason.STALE_CONTEXT, failure.diagnostic.localReason)
    }
    @Test fun readyRequiresAuthenticationNetworkAndEndpoint() {
        val i = SecurityInputs(binding, startReady = true, bootstrapAuthenticationAccepted = true, networkReady = true, endpointValidated = true)
        for (bad in listOf(i.copy(bootstrapAuthenticationAccepted = false), i.copy(networkReady = false), i.copy(endpointValidated = false)))
            assertEquals(SecurityReason.SECURITY_INPUT_MISSING, assertThrows(SecurityFailure::class.java) { SecurityRouter.select(bad) }.diagnostic.localReason)
    }
    @Test fun fixedInputNeedsPeerAgreementInsteadOfPhysicalTransport() {
        val provider=InternalSecurityProfiles.fixedSetupProvider(SetupInputAgreement(binding.peer,InternalSecurityProfiles.referenceId,"synthetic-evidence"))
        val code=provider.input(binding)!!
        assertArrayEquals(byteArrayOf(0x33,0x39,0x33,0x39),code)
        code.fill(0)
        assertArrayEquals(byteArrayOf(0x33,0x39,0x33,0x39),provider.input(binding))
        assertNull(provider.input(binding.copy(peer="another-peer")))
        assertFalse(InternalSecurityProfiles.entryGate)
        assertFalse(InternalSecurityProfiles.productionReady)
        for(transport in SecurityTransport.entries){
            val i=SecurityInputs(binding,bootstrapTransport=transport,endpointSource=SecurityEntry.DISCOVERY,
                features=1UL shl 38,pairedCapabilityGate=true)
            assertEquals(SecurityReason.SETUP_INPUT_UNAVAILABLE,assertThrows(SecurityFailure::class.java){SecurityRouter.select(i)}.diagnostic.localReason)
            assertEquals(SecurityAction.PAIR_VERIFY,SecurityRouter.select(i.copy(record=PairRecordStatus.ACTIVE)))
        }
    }
    @Test fun equalDiscoveryInputsAreIndependentOfPhysicalBootstrap() {
        for(features in listOf<ULong?>(null,0UL,1UL shl 38))for(gate in listOf<Boolean?>(null,false,true)){
            val results=SecurityTransport.entries.map{transport->
                try { SecurityRouter.route(SecurityInputs(binding,bootstrapTransport=transport,
                    endpointSource=SecurityEntry.DISCOVERY,features=features,pairedCapabilityGate=gate)).name }
                catch(t:SecurityFailure){t.diagnostic.localReason.name}
            }
            assertEquals("features=$features gate=$gate",1,results.toSet().size)
        }
    }
    @Test fun executionMaterialFailuresNameTheirActualLayerAndPhase() {
        val aa=assertThrows(SecurityFailure::class.java){SecurityRequirements.requireAaTrust()}.diagnostic
        assertEquals(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE,aa.localReason)
        assertEquals("iAP2-AA",aa.layer);assertEquals("certificate-verification",aa.phase)
        val schema=assertThrows(SecurityFailure::class.java){SecurityRequirements.requireSapSignatureSchema()}.diagnostic
        assertEquals(SecurityReason.SAP_SIGNATURE_SCHEMA_UNSUPPORTED,schema.localReason)
        assertEquals("AirPlay-SAP",schema.layer);assertEquals("auth-setup",schema.phase)
        val sap=assertThrows(SecurityFailure::class.java){SecurityRequirements.requireSapTrust()}.diagnostic
        assertEquals(SecurityReason.SAP_TRUST_MATERIAL_UNAVAILABLE,sap.localReason)
        assertEquals("AirPlay-SAP",sap.layer);assertEquals("auth-setup",sap.phase)
    }

    @Test fun otherCuMechanismsNeverFallThroughToSapOrReturnAnExchangeAction() {
        for (bit in listOf(43, 46, 48)) for (gate in listOf<Boolean?>(null, false, true))
            for (record in PairRecordStatus.entries) {
                val input = SecurityInputs(binding, endpointSource = SecurityEntry.DISCOVERY,
                    features = 1UL shl bit, pairedCapabilityGate = gate, record = record,
                    setupProviderBound = true)
                val failure = assertThrows(SecurityFailure::class.java) { SecurityRouter.select(input) }
                assertEquals("bit=$bit gate=$gate record=$record",
                    SecurityReason.PAIRING_MECHANISM_UNSUPPORTED, failure.diagnostic.localReason)
            }
    }

    @Test fun directCuStillRequiresItsExistingPolicyAndSetupMaterial() {
        for (extra in listOf(0UL, 1UL shl 43, 1UL shl 46, 1UL shl 48)) {
            val input = SecurityInputs(binding, endpointSource = SecurityEntry.DISCOVERY,
                features = (1UL shl 38) or extra)
            assertEquals(SecurityReason.SECURITY_POLICY_UNRESOLVED,
                assertThrows(SecurityFailure::class.java) { SecurityRouter.select(input) }.diagnostic.localReason)
            assertEquals(SecurityReason.SETUP_INPUT_UNAVAILABLE,
                assertThrows(SecurityFailure::class.java) {
                    SecurityRouter.select(input.copy(pairedCapabilityGate = true))
                }.diagnostic.localReason)
            assertEquals(SecurityAction.PAIR_VERIFY,
                SecurityRouter.select(input.copy(pairedCapabilityGate = true, record = PairRecordStatus.ACTIVE)))
        }
    }

    @Test fun newCapabilityClassificationDoesNotOverrideBindingOrSourceChecks() {
        val input = SecurityInputs(binding, endpointSource = SecurityEntry.DISCOVERY,
            features = 1UL shl 46)
        for ((bad, reason) in listOf(
            input.copy(expectedBinding = binding.copy(epoch = 8)) to SecurityReason.STALE_CONTEXT,
            input.copy(identityConflict = true) to SecurityReason.PEER_BINDING_CONFLICT,
            input.copy(controlVersion = 3) to SecurityReason.CONTROL_SCHEMA_UNSUPPORTED,
            input.copy(endpointSource = SecurityEntry.UNKNOWN) to SecurityReason.ENDPOINT_SOURCE_UNSUPPORTED)) {
            assertEquals(reason, assertThrows(SecurityFailure::class.java) { SecurityRouter.select(bad) }.diagnostic.localReason)
        }
        assertEquals(SecurityAction.WAIT_START_PARAMETERS,
            SecurityRouter.select(input.copy(endpointSource = SecurityEntry.IAP_START)))
    }

    @Test fun readyBootstrapRemainsAnIndependentBoundedEntryNotACapabilityInference() {
        val input = SecurityInputs(binding, startReady = true, bootstrapAuthenticationAccepted = true,
            networkReady = true, endpointValidated = true, features = 1UL shl 46,
            record = PairRecordStatus.ACTIVE)
        assertEquals(SecurityAction.PAIR_VERIFY, SecurityRouter.select(input))
        assertEquals(SecurityReason.SECURITY_INPUT_MISSING,
            assertThrows(SecurityFailure::class.java) {
                SecurityRouter.select(input.copy(bootstrapAuthenticationAccepted = false))
            }.diagnostic.localReason)
    }
}
