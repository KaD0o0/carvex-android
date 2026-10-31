package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** In-memory synthetic certificates only; no captured identities, keys or credentials. */
class ExperimentalAaPolicyTest {
    private val policy=AccessoryAuth.CertificatePolicy.EXPERIMENTAL_CAPTURED_RSA1024_SHA1_OR_P256
    private class Accessory(bits:Int=1024,ec:Boolean=false) {
        val pair=if(ec)KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
            else KeyPairGenerator.getInstance("RSA").apply{initialize(bits)}.generateKeyPair()
        private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        private fun der(tag:Int,body:ByteArray):ByteArray {
            val n=body.size
            val length=if(n<128)byteArrayOf(n.toByte())else if(n<=255)byteArrayOf(0x81.toByte(),n.toByte())
                else byteArrayOf(0x82.toByte(),(n ushr 8).toByte(),n.toByte())
            return byteArrayOf(tag.toByte())+length+body
        }
        val certificate:ByteArray
        val container:ByteArray
        init {
            // The RSA certificate is signed with SHA256. Its own signature OID must not
            // accidentally select the independently configured AA SHA1-digest contract.
            val algorithm=hex(if(ec)"300a06082a8648ce3d040302"else "300d06092a864886f70d01010b0500")
            val name=der(0x30,der(0x31,der(0x30,hex("0603550403")+der(0x0c,"synthetic AA policy test".toByteArray()))))
            val validity=der(0x30,der(0x17,"250101000000Z".toByteArray())+der(0x17,"350101000000Z".toByteArray()))
            val tbs=der(0x30,hex("a003020102020101")+algorithm+name+validity+name+pair.public.encoded)
            val signature=sign(if(ec)"SHA256withECDSA"else "SHA256withRSA",tbs)
            certificate=der(0x30,tbs+algorithm+der(3,byteArrayOf(0)+signature))
            container=der(0x30,hex("06092a864886f70d010702")+der(0xa0,der(0x30,
                hex("0201013100")+der(0x30,hex("06092a864886f70d010701"))+der(0xa0,certificate)+hex("3100"))))
        }
        fun sign(algorithm:String,data:ByteArray):ByteArray=Signature.getInstance(algorithm).run{
            initSign(pair.private);update(data);sign()
        }
        fun signDigest(challenge:ByteArray)=sign("NONEwithRSA",hex("3021300906052b0e03021a05000414")+challenge)
    }

    @Test fun capturedRsaSubsetRequiresExplicitExperimentalPolicyAndKeepsItsSchema() {
        val accessory=Accessory()
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.parseAuthenticationCertificate(accessory.container)}
        val parsed=AccessoryAuth.parseAuthenticationCertificate(accessory.container,policy)
        assertEquals(AccessoryAuth.ChallengeSchema.RSA_SHA1_20,parsed.schema)
        val challenge=AccessoryAuth.challenge(parsed.certificate,parsed.schema)
        assertEquals(20,challenge.size)
        val signature=accessory.signDigest(challenge)
        assertEquals(128,signature.size)
        assertTrue(AccessoryAuth.verifyChallenge(parsed.certificate,challenge,signature,parsed.schema))
        assertFalse(AccessoryAuth.verifyChallenge(parsed.certificate,challenge,accessory.sign("SHA1withRSA",challenge),parsed.schema))
        assertFalse(AccessoryAuth.verifyChallenge(parsed.certificate,challenge+0,signature,parsed.schema))
        assertFalse(AccessoryAuth.verifyChallenge(parsed.certificate,challenge,signature.copyOf().apply{this[0]=(this[0].toInt() xor 1).toByte()},parsed.schema))
        // No downstream call may discard the selected schema and assume the strict default.
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.challenge(parsed.certificate)}
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.verifyChallenge(parsed.certificate,challenge,signature)}
    }

    @Test fun experimentalPolicyDoesNotMapArbitraryRsaOrRawCertificatesToSha1() {
        val wider=Accessory(2048)
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.parseAuthenticationCertificate(wider.container,policy)}
        val capturedShape=Accessory()
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.parseAuthenticationCertificate(capturedShape.certificate,policy)}
    }

    @Test fun p256ContractRemainsAvailableInBothPolicies() {
        val accessory=Accessory(ec=true)
        for(selected in AccessoryAuth.CertificatePolicy.entries){
            val parsed=AccessoryAuth.parseAuthenticationCertificate(accessory.container,selected)
            assertEquals(AccessoryAuth.ChallengeSchema.AA_P256_SHA256_DIGEST_V1,parsed.schema)
            val challenge=AccessoryAuth.challenge(parsed.certificate,parsed.schema)
            assertEquals(32,challenge.size)
            assertTrue(AccessoryAuth.verifyChallenge(parsed.certificate,challenge,
                accessory.sign("NONEwithECDSAinP1363Format",challenge),parsed.schema))
        }
    }
}
