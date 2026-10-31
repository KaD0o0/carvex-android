package io.github.iamr0s.carvex.carplay.core

import java.security.cert.*
import java.io.ByteArrayInputStream
import java.util.*
import org.junit.Assert.*
import org.junit.Test

class AuthTrustTest {
    private val fixtures=Properties().apply{load(AuthTrustTest::class.java.getResourceAsStream("/e18-synthetic-trust.properties"))}
    private fun cert(key:String)=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(fixtures.getProperty(key)))) as X509Certificate
    private val context=SecurityBinding(1,"synthetic-peer",1)
    private val now=Date(1_800_000_000_000L)
    private fun policy(layer:AuthLayer=AuthLayer.IAP2_AA,anchors:Set<TrustAnchor> = setOf(TrustAnchor(cert("root"),null)),
        purpose:Boolean=true,revoked:Boolean=false,custom:Boolean?=null)=object:AuthTrustPolicy {
        override val layer=layer
        override val policyId="SYNTHETIC_TRUST_TEST"
        override val revision=1L
        override val synthetic=true
        override val anchors=anchors
        override val criticalExtensionDecoders=mutableMapOf<String,(X509Certificate,ByteArray)->Boolean>(
            "2.5.29.19" to { c,_ -> c.basicConstraints >= -1 }).apply{
            if(custom!=null)put("1.2.3.4.5"){_,bytes-> custom && bytes.contentEquals(byteArrayOf(4,3,4,1,7))}
        }
        override fun acceptsPurpose(leaf:X509Certificate,schema:String)=purpose && schema=="synthetic"
        override fun revoked(certificate:X509Certificate)=revoked
    }
    private fun verify(p:AuthTrustPolicy?,leaf:String="leaf",time:Date?=now,signature:Boolean=true)=
        AuthTrustVerifier.verify(p,AuthLayer.IAP2_AA,"synthetic",listOf(cert(leaf),cert("intermediate")),time,context){signature}
    private fun failure(reason:SecurityReason,block:()->Unit)=assertEquals(reason,assertThrows(SecurityFailure::class.java){block()}.diagnostic.localReason)

    @Test fun syntheticChainIsValidatedButCannotActivateProduction() {
        val result=verify(policy())
        assertTrue(result.synthetic)
        val sap=AuthTrustVerifier.verify(policy(AuthLayer.AIRPLAY_SAP),AuthLayer.AIRPLAY_SAP,"synthetic",listOf(cert("leaf"),cert("intermediate")),now,context){true}
        failure(SecurityReason.TRUST_PURPOSE_UNSUPPORTED){AuthTrustVerifier.requireProductionPair(result,sap,context)}
    }
    @Test fun missingRootWrongPurposeAndUnrelatedAnchorReject() {
        failure(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE){verify(null)}
        failure(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE){verify(policy(anchors=emptySet()))}
        failure(SecurityReason.TRUST_PURPOSE_UNSUPPORTED){verify(policy(purpose=false))}
        failure(SecurityReason.CERT_CHAIN_INVALID){verify(policy(anchors=setOf(TrustAnchor(cert("leaf"),null))))}
        failure(SecurityReason.SAP_TRUST_MATERIAL_UNAVAILABLE){AuthTrustVerifier.verify(null,AuthLayer.AIRPLAY_SAP,"synthetic",listOf(cert("leaf")),now,context){true}}
    }
    @Test fun criticalExtensionRequiresDecoderAndCorrectValue() {
        failure(SecurityReason.CERT_CRITICAL_EXTENSION_UNSUPPORTED){verify(policy(),"unknown")}
        failure(SecurityReason.CERT_EXTENSION_INVALID){verify(policy(custom=false),"unknown")}
        assertTrue(verify(policy(custom=true),"unknown").synthetic)
    }
    @Test fun trustedTimeRevocationAndCurrentSignatureAreIndependentGates() {
        failure(SecurityReason.TRUST_TIME_UNAVAILABLE){verify(policy(),time=null)}
        failure(SecurityReason.CERT_TIME_INVALID){verify(policy(),time=Date(0))}
        failure(SecurityReason.CERT_REVOKED){verify(policy(revoked=true))}
        failure(SecurityReason.AA_SIGNATURE_INVALID){verify(policy(),signature=false)}
    }
}
