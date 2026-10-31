package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class MfiSignatureTest {
    private val schema=AccessoryAuth.SapSignatureSchema.EXPERIMENTAL_RSA_PKCS1_SHA1_PUBLIC_KEYS_V1
    @Test fun explicitExperimentalRsaSchemaBindsBothPublicKeysAndRejectsOtherDigests(){
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val server=ByteArray(32){it.toByte()};val client=ByteArray(32){(it+32).toByte()}
        val signature=Signature.getInstance("SHA1withRSA").run{initSign(pair.private);update(server+client);sign()}
        assertTrue(AccessoryAuth.verifyMfiSignature(pair.public,schema,server,client,signature))
        assertFalse(AccessoryAuth.verifyMfiSignature(pair.public,schema,client,server,signature))
        val other=Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(server+client);sign()}
        assertFalse(AccessoryAuth.verifyMfiSignature(pair.public,schema,server,client,other))
        val ec=KeyPairGenerator.getInstance("EC").apply{initialize(256)}.generateKeyPair()
        assertFalse(AccessoryAuth.verifyMfiSignature(ec.public,schema,server,client,signature))
    }
    @Test fun sapDerivationIsTheIndependentSha1KnownAnswer(){
        // Python hashlib SHA1(label || bytes(range(32)))[0:16], independent of Crypto.hash.
        val shared=ByteArray(32){it.toByte()}
        fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(hex("8aff92634f0a103a75aa5dc3d24438c0"),AccessoryAuth.sapMaterial("AES-KEY",shared))
        assertArrayEquals(hex("95f4f8637bdaaf2d1042c9281b541d24"),AccessoryAuth.sapMaterial("AES-IV",shared))
    }
}
