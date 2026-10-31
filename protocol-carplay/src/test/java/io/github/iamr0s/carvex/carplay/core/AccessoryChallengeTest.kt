package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.math.BigInteger

class AccessoryChallengeTest {
    private fun sign(pair:KeyPair,algorithm:String,data:ByteArray)=Signature.getInstance(algorithm).run{
        initSign(pair.private);update(data);sign()
    }
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun rsa20And32RequireMatchingExplicitSchemasAndExactChallenge(){
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.schema(pair.public)}
        val schemas=listOf(
            Triple(AccessoryAuth.ChallengeSchema.RSA_SHA1_20,20,"3021300906052b0e03021a05000414"),
            Triple(AccessoryAuth.ChallengeSchema.RSA_SHA256_32,32,"3031300d060960864801650304020105000420"))
        schemas.forEach{(schema,size,prefix)->
            val challenge=ByteArray(size){(it+1).toByte()}
            val signature=sign(pair,"NONEwithRSA",hex(prefix)+challenge)
            assertTrue(AccessoryAuth.verifyChallenge(pair.public,challenge,signature,schema))
            assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge.copyOf().apply{this[0]=99},signature,schema))
            assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge+0,signature,schema))
            assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,signature.copyOf(signature.size-1),schema))
            assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,signature,AccessoryAuth.ChallengeSchema.ECDSA_SHA256_32))
        }
    }

    @Test fun p256RawRsUsesChallengeAsDigestAndRejectsDerAndDoubleHash(){
        val pair=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
        val schema=AccessoryAuth.ChallengeSchema.AA_P256_SHA256_DIGEST_V1
        assertEquals(schema,AccessoryAuth.schema(pair.public))
        val challenge=ByteArray(32){it.toByte()}
        val signature=sign(pair,"NONEwithECDSAinP1363Format",challenge)
        assertEquals(64,signature.size)
        assertTrue(AccessoryAuth.verifyChallenge(pair.public,challenge,signature,schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,sign(pair,"NONEwithECDSA",challenge),schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,sign(pair,"SHA256withECDSAinP1363Format",challenge),schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge.copyOf().apply{this[1]=99},signature,schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge.copyOf(20),signature,schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,byteArrayOf(),schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,ByteArray(72){0x7f},schema))
        assertFalse(AccessoryAuth.verifyChallenge(pair.public,challenge,signature,AccessoryAuth.ChallengeSchema.RSA_SHA256_32))
    }

    @Test fun p256SerialIsUppercaseHexOfActualSixteenByteMagnitude(){
        val serial=BigInteger("0102030405060708090A0B0C0D0E0F10",16)
        val identifier="0102030405060708090A0B0C0D0E0F10".toByteArray(Charsets.US_ASCII)
        assertTrue(AccessoryAuth.matchesP256Serial(serial,identifier))
        assertTrue(AccessoryAuth.matchesP256Serial(BigInteger("FEDCBA98765432100123456789ABCDEF",16),
            "FEDCBA98765432100123456789ABCDEF".toByteArray(Charsets.US_ASCII)))
        assertFalse(AccessoryAuth.matchesP256Serial(serial,identifier.toString(Charsets.US_ASCII).lowercase().toByteArray()))
        assertFalse(AccessoryAuth.matchesP256Serial(serial,identifier+0))
        assertFalse(AccessoryAuth.matchesP256Serial(serial,serial.toByteArray()))
        assertFalse(AccessoryAuth.matchesP256Serial(serial,identifier.copyOf().apply{this[0]='F'.code.toByte()}))
        assertFalse(AccessoryAuth.matchesP256Serial(BigInteger.ONE,"00000000000000000000000000000001".toByteArray()))
        assertFalse(AccessoryAuth.matchesP256Serial(serial.negate(),identifier))
    }

    @Test fun unsupportedPublicKeysAreNotTreatedAsRsa(){
        val dsa=KeyPairGenerator.getInstance("DSA").apply{initialize(1024)}.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.schema(dsa.public)}
        val ec384=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp384r1"))}.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java){AccessoryAuth.schema(ec384.public)}
    }
}
