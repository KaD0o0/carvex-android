package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class IapCertificateTransactionTest {
    private val schema=AccessoryAuth.ChallengeSchema.AA_P256_SHA256_DIGEST_V1
    private val serial="0102030405060708090A0B0C0D0E0F10".toByteArray(Charsets.US_ASCII)
    /** A synthetic, self-signed test certificate. Its parsing never asserts chain trust. */
    private fun certificate()=AccessoryAuth.leaf(checkNotNull(javaClass.getResourceAsStream("/synthetic-aa-p256.pem")).use{it.readBytes()})

    @Test fun p256AaSchemaRequiresACompleteDerPkcs7Container(){
        val container=checkNotNull(javaClass.getResourceAsStream("/synthetic-aa-p256.p7b")).use{it.readBytes()}
        val parsed=AccessoryAuth.parseAuthenticationCertificate(container)
        assertEquals(schema,parsed.schema)
        assertEquals(certificate().serialNumber,parsed.certificate.serialNumber)
        assertThrows(Exception::class.java){AccessoryAuth.parseAuthenticationCertificate(container+0)}
        assertThrows(Exception::class.java){AccessoryAuth.parseAuthenticationCertificate(container.copyOf(container.size-1))}
        assertThrows(Exception::class.java){AccessoryAuth.parseAuthenticationCertificate(certificate().encoded)}
        assertThrows(Exception::class.java){AccessoryAuth.parseAuthenticationCertificate(ByteArray(16385))}
    }

    @Test fun advertisedSerialExtensionRequestsOnceThenUsesBareRequestForFullCertificate(){
        val transaction=IapAuthentication.CertificateTransaction(4,9,setOf(0xaa06))
        val first=transaction.requestFields(4,9)
        assertArrayEquals(byteArrayOf(0x40,0x40,0,10,0xaa.toByte(),0,0,4,0,0),IapWire.csm(0xaa00,first))
        assertEquals(IapAuthentication.CertificateTransaction.State.SERIAL_REQUESTED,transaction.state)
        val fields=IapFields(listOf(0 to serial.copyOf()))
        assertTrue(transaction.onSerial(fields,4,9).isEmpty())
        assertEquals(IapAuthentication.CertificateTransaction.State.CERT_REQUESTED,transaction.state)
        assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.OPAQUE_HINT,transaction.serialBinding)
        fields[0]!![0]='F'.code.toByte()
        assertArrayEquals(serial,transaction.serialIdentifier)
        transaction.serialIdentifier!![0]='F'.code.toByte()
        assertArrayEquals(serial,transaction.serialIdentifier)
        transaction.acceptCertificate(certificate(),schema,4,9)
        assertEquals(IapAuthentication.CertificateTransaction.State.CERT_RECEIVED,transaction.state)
        assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.SERIAL_MATCHED,transaction.serialBinding)
    }

    @Test fun binary16HintTakesColdCertificatePathWithoutInventingASerialMapping(){
        // Public synthetic bytes, deliberately different from this certificate's serial.
        val hint=ByteArray(16){(0x80+it).toByte()}
        val transaction=IapAuthentication.CertificateTransaction(4,9,setOf(0xaa06))
        transaction.requestFields(4,9)
        val request=transaction.onSerial(IapFields(listOf(0 to hint)),4,9)
        assertArrayEquals(byteArrayOf(0x40,0x40,0,6,0xaa.toByte(),0),IapWire.csm(0xaa00,request))
        assertEquals(IapAuthentication.CertificateTransaction.State.CERT_REQUESTED,transaction.state)
        assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.OPAQUE_HINT,transaction.serialBinding)
        hint.fill(0)
        assertTrue(transaction.serialIdentifier!!.any{it!=0.toByte()})
        transaction.acceptCertificate(certificate(),schema,4,9)
        assertEquals(IapAuthentication.CertificateTransaction.State.CERT_RECEIVED,transaction.state)
        // Receiving a complete certificate has not validated the opaque hint or a signature.
        assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.OPAQUE_HINT,transaction.serialBinding)
        val fresh=AccessoryAuth.challenge(certificate(),schema)
        assertEquals(32,fresh.size)
        assertFalse(AccessoryAuth.verifyChallenge(certificate(),fresh,ByteArray(64),schema))
    }

    @Test fun otherUnsupportedHintLengthsRemainOpaqueAndCannotSupplyACachedCertificate(){
        for(length in listOf(1,17,31,64,256)){
            val transaction=IapAuthentication.CertificateTransaction(4,9,setOf(0xaa06))
            transaction.requestFields(4,9)
            assertTrue(transaction.onSerial(IapFields(listOf(0 to ByteArray(length){0x80.toByte()})),4,9).isEmpty())
            assertEquals(IapAuthentication.CertificateTransaction.State.CERT_REQUESTED,transaction.state)
            transaction.acceptCertificate(certificate(),schema,4,9)
            assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.OPAQUE_HINT,transaction.serialBinding)
        }
    }

    @Test fun missingCurrentCapabilitiesUsesBareCertificateRequestAndDoesNotAcceptSerial(){
        for(capabilities in listOf(null,emptySet(),setOf(0xaa01,0xaa03))){
            val transaction=IapAuthentication.CertificateTransaction(1,1,capabilities)
            assertTrue(transaction.requestFields(1,1).isEmpty())
            assertThrows(IllegalStateException::class.java){transaction.onSerial(IapFields(listOf(0 to serial)),1,1)}
            assertEquals(IapAuthentication.CertificateTransaction.State.FAILED,transaction.state)
        }
        val transaction=IapAuthentication.CertificateTransaction(1,1,null)
        assertEquals(IapAuthentication.CertificateTransaction.SerialBinding.NONE,transaction.serialBinding)
        transaction.requestFields(1,1)
        transaction.acceptCertificate(certificate(),schema,1,1)
    }

    @Test fun unsolicitedDuplicateAndMalformedSerialResponsesCannotIssueAnotherRequest(){
        val transaction=IapAuthentication.CertificateTransaction(1,2,setOf(0xaa06))
        transaction.requestFields(1,2)
        transaction.onSerial(IapFields(listOf(0 to serial)),1,2)
        assertThrows(IllegalStateException::class.java){transaction.onSerial(IapFields(listOf(0 to serial)),1,2)}
        for(fields in listOf(IapFields(emptyList()),IapFields(listOf(1 to serial)),
            IapFields(listOf(0 to byteArrayOf())),IapFields(listOf(0 to ByteArray(257))),
            IapFields(listOf(0 to serial,0 to serial)))){
            val malformed=IapAuthentication.CertificateTransaction(1,2,setOf(0xaa06))
            malformed.requestFields(1,2)
            assertThrows(IllegalStateException::class.java){malformed.onSerial(fields,1,2)}
        }
    }

    @Test fun fullCertificateBeforeSerialAndSerialMismatchFailClosed(){
        val premature=IapAuthentication.CertificateTransaction(1,2,setOf(0xaa06))
        premature.requestFields(1,2)
        assertThrows(IllegalStateException::class.java){premature.acceptCertificate(certificate(),schema,1,2)}
        for(identifier in listOf(serial.copyOf().apply{this[0]='F'.code.toByte()},
            serial.copyOf().apply{this[0]='G'.code.toByte()},ByteArray(32){0x80.toByte()},
            serial.toString(Charsets.US_ASCII).lowercase().toByteArray(),serial+0)){
            val mismatch=IapAuthentication.CertificateTransaction(1,2,setOf(0xaa06))
            mismatch.requestFields(1,2)
            mismatch.onSerial(IapFields(listOf(0 to identifier)),1,2)
            assertThrows(IllegalStateException::class.java){mismatch.acceptCertificate(certificate(),schema,1,2)}
            assertEquals(IapAuthentication.CertificateTransaction.State.FAILED,mismatch.state)
            assertNotEquals(IapAuthentication.CertificateTransaction.SerialBinding.SERIAL_MATCHED,mismatch.serialBinding)
        }
    }

    @Test fun opaqueHintDoesNotRelaxGenerationEpochOrCertificateDeadline(){
        for(context in listOf(2 to 7L,1 to 8L)){
            val stale=IapAuthentication.CertificateTransaction(1,7,setOf(0xaa06))
            stale.requestFields(1,7)
            stale.onSerial(IapFields(listOf(0 to ByteArray(16))),1,7)
            assertThrows(IllegalStateException::class.java){stale.acceptCertificate(certificate(),schema,context.first,context.second)}
            assertEquals(IapAuthentication.CertificateTransaction.State.FAILED,stale.state)
        }
        var now=0L
        val expired=IapAuthentication.CertificateTransaction(1,7,setOf(0xaa06),10){now}
        expired.requestFields(1,7)
        expired.onSerial(IapFields(listOf(0 to ByteArray(16))),1,7)
        now=10_000_000
        assertThrows(IllegalStateException::class.java){expired.acceptCertificate(certificate(),schema,1,7)}
        assertEquals(IapAuthentication.CertificateTransaction.State.FAILED,expired.state)
    }

    @Test fun generationEpochDeadlineAndDuplicateCertificateInvalidateTheTransaction(){
        for(context in listOf(2 to 7L,1 to 8L)){
            val stale=IapAuthentication.CertificateTransaction(1,7,setOf(0xaa06))
            stale.requestFields(1,7)
            assertThrows(IllegalStateException::class.java){stale.onSerial(IapFields(listOf(0 to serial)),context.first,context.second)}
        }
        var now=0L
        val expired=IapAuthentication.CertificateTransaction(1,7,setOf(0xaa06),10){now}
        expired.requestFields(1,7);now=10_000_000
        assertThrows(IllegalStateException::class.java){expired.onSerial(IapFields(listOf(0 to serial)),1,7)}
        val duplicate=IapAuthentication.CertificateTransaction(1,7,null)
        duplicate.requestFields(1,7)
        duplicate.acceptCertificate(certificate(),schema,1,7)
        assertThrows(IllegalStateException::class.java){duplicate.acceptCertificate(certificate(),schema,1,7)}
        assertThrows(IllegalStateException::class.java){duplicate.requestFields(1,7)}
    }
}
