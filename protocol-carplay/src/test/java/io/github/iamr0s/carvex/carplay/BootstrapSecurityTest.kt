package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.*
import org.junit.Assert.*
import org.junit.Test

class BootstrapSecurityTest {
    private val binding=SecurityBinding(4,"synthetic-session-peer",9)
    private val source=BootstrapAuthentication(4,9,2,true) // Synthetic policy inputs, not a real AA result.
    private val metadata=ReceiverMetadata("pair-id",null,0UL)
    private fun bootstrap()=WifiBootstrap("test","password1",2,null,authentication=source)
        .resolved("192.0.2.1",7000,"02:11:22:33:44:55",null,metadata)

    @Test fun resolvedDiscoveryDoesNotCreateAStartReadySnapshotOnEitherTransport() {
        for(transport in listOf(SecurityTransport.BLUETOOTH_WIFI,SecurityTransport.USB_BOOTSTRAP_NCM)){
            val config=bootstrap().copy(bootstrapTransport=transport)
            assertFalse(config.startSessionReady)
            val inputs=config.securityInputs(binding,metadata,true,true)
            assertEquals(transport,inputs.bootstrapTransport)
            assertEquals(SecurityEntry.DISCOVERY,inputs.endpointSource)
            assertEquals(SecurityAction.AUTH_SETUP,SecurityRouter.select(inputs))
        }
    }
    @Test fun readyPrecedesDiscoveryFeatureAndGateButDoesNotSupplySetupCode() {
        val config=WifiBootstrap("test","password1",2,"192.0.2.1",7000,"pair-id",null).copy(authentication=source)
        val inputs=config.securityInputs(binding,metadata,true,true,pairedCapabilityGate=false)
        assertTrue(inputs.startReady)
        assertEquals(SecurityRoute.PAIRED,SecurityRouter.route(inputs))
        assertEquals(SecurityReason.SETUP_INPUT_UNAVAILABLE,assertThrows(SecurityFailure::class.java){SecurityRouter.select(inputs)}.diagnostic.localReason)
        assertEquals(SecurityAction.PAIR_VERIFY,SecurityRouter.select(inputs.copy(record=PairRecordStatus.ACTIVE)))
    }
    @Test fun acceptedStartSnapshotSurvivesLaterDiscoveryEndpointSelection() {
        val config=WifiBootstrap("test","password1",2,"192.0.2.1",7000,"pair-id",null).copy(authentication=source)
            .resolved("192.0.2.2",7000,"02:11:22:33:44:55",null,metadata)
        assertEquals(SecurityEntry.DISCOVERY,config.endpointSource)
        assertEquals("pair-id",config.startSession!!.receiver)
        assertTrue(config.startSessionReady)
        val inputs=config.securityInputs(binding,metadata,true,true,pairedCapabilityGate=false)
        assertEquals(SecurityRoute.PAIRED,SecurityRouter.route(inputs))
    }
    @Test fun discoveryMissingGateAndFeaturesRemainDistinctFromMissingProvider() {
        val config=bootstrap()
        fun input(meta:ReceiverMetadata,gate:Boolean?=null)=config.securityInputs(binding,meta,true,true,gate)
        fun failure(reason:SecurityReason,inputs:SecurityInputs)=assertEquals(reason,assertThrows(SecurityFailure::class.java){SecurityRouter.select(inputs)}.diagnostic.localReason)
        failure(SecurityReason.SECURITY_INPUT_MISSING,input(metadata.copy(features=null)))
        failure(SecurityReason.SECURITY_POLICY_UNRESOLVED,input(metadata.copy(features=1UL shl 38)))
        failure(SecurityReason.SETUP_INPUT_UNAVAILABLE,input(metadata.copy(features=1UL shl 38),true))
    }
    @Test fun oldGenerationAndEpochCannotBecomeCurrentReadyInputs() {
        val config=bootstrap()
        for(stale in listOf(binding.copy(generation=3),binding.copy(epoch=8))){
            assertEquals(SecurityReason.STALE_CONTEXT,assertThrows(SecurityFailure::class.java){
                SecurityRouter.route(config.securityInputs(stale,metadata,true,true))
            }.diagnostic.localReason)
        }
    }
    @Test fun revokedLiveSourceRejectsEvenWhenBothHistoricalSnapshotsStillMatch(){
        assertEquals(SecurityReason.STALE_CONTEXT,assertThrows(SecurityFailure::class.java){
            bootstrap().securityInputs(binding,metadata,true,true,sourceContextCurrent=false)
        }.diagnostic.localReason)
    }
    @Test fun explicitExperimentAcceptsVerifiedChallengeButNeverMissingOrBadEvidence() {
        val verified=bootstrap().copy(authentication=source.copy(aaTrusted=false,challengeSignatureVerified=true))
        verified.requireAuthenticatedBootstrap(experimentalIntegrityOnly=true)
        assertFalse(verified.authentication!!.aaTrusted)
        assertTrue(verified.securityInputs(binding,metadata,true,true,experimentalIntegrityOnly=true).bootstrapAuthenticationAccepted)
        assertFalse(verified.securityInputs(binding,metadata,true,true).bootstrapAuthenticationAccepted)
        for(config in listOf(bootstrap().copy(authentication=null),bootstrap().copy(authentication=source.copy(aaTrusted=false)))){
            assertEquals(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE,assertThrows(SecurityFailure::class.java){
                config.requireAuthenticatedBootstrap(experimentalIntegrityOnly=true)
            }.diagnostic.localReason)
        }
    }
    @Test fun missingBootstrapTrustCannotBeOverriddenByDiscoveryOrProductionReadyMetadata() {
        for(config in listOf(bootstrap().copy(authentication=null),bootstrap().copy(authentication=source.copy(aaTrusted=false)))){
            val error=assertThrows(SecurityFailure::class.java){config.requireAuthenticatedBootstrap()}
            assertEquals("iAP2-AA",error.diagnostic.layer)
            assertEquals(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE,error.diagnostic.localReason)
        }
        bootstrap().requireAuthenticatedBootstrap()
    }
    @Test fun startPeerDeclarationsCannotChangeRouteOrProvideTrust(){
        for(mutual in listOf(null,false,true)){
            val endpoint=BootstrapEndpoint("192.0.2.1",7000,"pair-id",null,
                startSessionPeerInfo=StartSessionPeerInfo("peer-version","peer-sdk",mutual))
            val config=WifiBootstrap("test","password1",2,endpoint,authentication=source)
            assertEquals(SecurityRoute.PAIRED,SecurityRouter.route(config.securityInputs(binding,metadata,true,true)))
            val missing=config.copy(authentication=null)
            assertEquals(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE,assertThrows(SecurityFailure::class.java){
                missing.requireAuthenticatedBootstrap()
            }.diagnostic.localReason)
            assertEquals(SecurityReason.STALE_CONTEXT,assertThrows(SecurityFailure::class.java){
                SecurityRouter.route(config.securityInputs(binding.copy(epoch=8),metadata,true,true))
            }.diagnostic.localReason)
        }
    }
}
