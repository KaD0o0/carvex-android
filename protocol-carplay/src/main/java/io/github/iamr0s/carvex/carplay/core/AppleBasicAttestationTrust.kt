package io.github.iamr0s.carvex.carplay.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import java.util.Locale
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.Certificate as BcCertificate
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier

/**
 * 24A446 Apple Basic Attestation User policy.
 *
 * The firmware validates exactly leaf -> intermediate -> BAA User Root with
 * SecPolicyCreateAppleBasicAttestationUser. The policy pins the BAA User root
 * by SHA-256, checks only the leaf validity period, and does not enable
 * revocation, leaf-marker, certificate-version-extension, or certificate
 * policy options.
 */
object AppleBasicAttestationTrust {
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private const val POLICY_OID = "1.2.840.113635.100.1.85"
    private const val POLICY_NAME = "BAA-UCRT"
    private const val IAP_AUTH_V3_OID = "1.2.840.113635.100.6.36"
    private const val IAP_SW_AUTH_OID = "1.2.840.113635.100.6.59.1"
    private const val MFI4_AUTH_OID = "1.2.840.113635.100.6.71.1"
    private const val ROOT_SHA256_HEX =
        "03751c80fcbe5819d170d267ce1ad6d094407c91d873d7a6562de3666d3594c6"

    private const val USER_ROOT_DER_BASE64 =
        "MIICGjCCAaGgAwIBAgIIO0EdkA1/e+swCgYIKoZIzj0EAwMwUzEnMCUGA1UEAwweQmFzaWMgQXR0ZXN0YXRpb24gVXNlciBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9ybmlhMB4XDTE3MDQxOTIxNDE1NloXDTMyMDMyMjAwMDAwMFowUzEnMCUGA1UEAwweQmFzaWMgQXR0ZXN0YXRpb24gVXNlciBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9ybmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE639EEPCGvFOPAbzd5eGJM46/I+N1rpRhPBc2+hvNC5vlVyv1hVNPSTIcF0sLPgCeEEQqGIZsUPO48ECh03YzXUT86015HwktM439bWGKGE/VRepW8S8EDRLh6+83XZ9do0IwQDAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBSD5aMhnrB0w/lhkP2XTiMQdqSj8jAOBgNVHQ8BAf8EBAMCAQYwCgYIKoZIzj0EAwMDZwAwZAIwQol5uLqv1SXY9G1wHSx2EYIULFlLssYTnTwbSlZPj2yF9cgk7xJO8bw6nTklgnHhAjBgMvsir7UEVzw7uxn8y3GGf9KSCFV5scySrYDZnJhETYlcyPYSFsZIerKNPP6v28Y="

    enum class Reason {
        NO_MATERIAL,
        MALFORMED_CERTIFICATE,
        DUPLICATE_EXTENSION,
        ROOT_HASH_MISMATCH,
        SUBJECT_EMPTY,
        ISSUER_LINKAGE_INVALID,
        AUTHORITY_KEY_ID_INVALID,
        BASIC_CONSTRAINTS_INVALID,
        KEY_USAGE_INVALID,
        CRITICAL_EXTENSION_UNSUPPORTED,
        WEAK_KEY,
        WEAK_SIGNATURE,
        LEAF_TIME_INVALID,
        SIGNATURE_INVALID,
    }

    sealed interface Result {
        data class Verified(
            val leaf: X509Certificate,
            val intermediate: X509Certificate,
            val root: X509Certificate,
            val authVersion: Int,
        ) : Result

        data class Rejected(val reason: Reason) : Result
    }

    private data class ParsedCertificate(
        val certificate: X509Certificate,
        val extensionOids: Set<String>,
    )

    val root: X509Certificate by lazy {
        parseSingleCertificate(Base64.getDecoder().decode(USER_ROOT_DER_BASE64)).certificate
    }

    val rootSha256: ByteArray
        get() = MessageDigest.getInstance("SHA-256").digest(root.encoded)

    val rootSha256Hex: String get() = ROOT_SHA256_HEX
    val policyId: String get() = POLICY_OID
    val policyName: String get() = POLICY_NAME

    /**
     * Verify the two certificates carried by AA01 for mode 1. The firmware
     * supplies the root from its anchor store, never from AA01.
     */
    fun verify(
        leafDer: ByteArray?,
        intermediateDer: ByteArray?,
        now: Date = Date(),
    ): Result = verifyWithRoot(
        leafDer = leafDer,
        intermediateDer = intermediateDer,
        rootDer = root.encoded,
        expectedRootSha256 = rootSha256,
        now = now,
    )

    /** Injectable root is limited to tests; production callers use [verify]. */
    internal fun verifyWithRoot(
        leafDer: ByteArray?,
        intermediateDer: ByteArray?,
        rootDer: ByteArray?,
        expectedRootSha256: ByteArray,
        now: Date = Date(),
    ): Result {
        if (leafDer == null || leafDer.isEmpty() ||
            intermediateDer == null || intermediateDer.isEmpty() ||
            rootDer == null || rootDer.isEmpty()
        )
            return Result.Rejected(Reason.NO_MATERIAL)
        val leafBytes = leafDer
        val intermediateBytes = intermediateDer
        val rootBytes = rootDer

        val leaf = try {
            parseSingleCertificate(leafBytes)
        } catch (_: DuplicateExtensionException) {
            return Result.Rejected(Reason.DUPLICATE_EXTENSION)
        } catch (_: Exception) {
            return Result.Rejected(Reason.MALFORMED_CERTIFICATE)
        }
        val intermediate = try {
            parseSingleCertificate(intermediateBytes)
        } catch (_: DuplicateExtensionException) {
            return Result.Rejected(Reason.DUPLICATE_EXTENSION)
        } catch (_: Exception) {
            return Result.Rejected(Reason.MALFORMED_CERTIFICATE)
        }
        val root = try {
            parseSingleCertificate(rootBytes)
        } catch (_: DuplicateExtensionException) {
            return Result.Rejected(Reason.DUPLICATE_EXTENSION)
        } catch (_: Exception) {
            return Result.Rejected(Reason.MALFORMED_CERTIFICATE)
        }

        val actualRootSha256 = MessageDigest.getInstance("SHA-256").digest(root.certificate.encoded)
        if (!MessageDigest.isEqual(actualRootSha256, expectedRootSha256))
            return Result.Rejected(Reason.ROOT_HASH_MISMATCH)

        val certs = listOf(leaf, intermediate, root)
        if (certs.any { it.certificate.subjectX500Principal.name.isBlank() })
            return Result.Rejected(Reason.SUBJECT_EMPTY)
        if (leaf.certificate.issuerX500Principal != intermediate.certificate.subjectX500Principal ||
            intermediate.certificate.issuerX500Principal != root.certificate.subjectX500Principal
        ) return Result.Rejected(Reason.ISSUER_LINKAGE_INVALID)

        if (!authorityKeyIdMatches(leaf.certificate, intermediate.certificate) ||
            !authorityKeyIdMatches(intermediate.certificate, root.certificate)
        ) return Result.Rejected(Reason.AUTHORITY_KEY_ID_INVALID)

        if (intermediate.certificate.basicConstraints < 0 ||
            root.certificate.basicConstraints < 0 ||
            root.certificate.basicConstraints == 0
        ) return Result.Rejected(Reason.BASIC_CONSTRAINTS_INVALID)

        if (!allowsCertificateSigning(intermediate.certificate) ||
            !allowsCertificateSigning(root.certificate)
        ) return Result.Rejected(Reason.KEY_USAGE_INVALID)

        if (certs.any { hasUnsupportedCriticalExtension(it) })
            return Result.Rejected(Reason.CRITICAL_EXTENSION_UNSUPPORTED)
        if (certs.any { isWeakKey(it.certificate) })
            return Result.Rejected(Reason.WEAK_KEY)
        if (certs.any { isWeakSignature(it.certificate) })
            return Result.Rejected(Reason.WEAK_SIGNATURE)

        val leafNow = now.time
        if (leafNow < leaf.certificate.notBefore.time || leafNow > leaf.certificate.notAfter.time)
            return Result.Rejected(Reason.LEAF_TIME_INVALID)

        try {
            leaf.certificate.verify(intermediate.certificate.publicKey)
            intermediate.certificate.verify(root.certificate.publicKey)
        } catch (_: Exception) {
            return Result.Rejected(Reason.SIGNATURE_INVALID)
        }

        return Result.Verified(
            leaf = leaf.certificate,
            intermediate = intermediate.certificate,
            root = root.certificate,
            authVersion = authVersion(leaf.certificate),
        )
    }

    /**
     * SecCertificateGetiAuthVersion @0x1895cfef8 checks extension presence in
     * this order. Version 2's legacy 15-byte marker is not guessed here.
     */
    fun authVersion(certificate: X509Certificate): Int {
        val oids = certificate.criticalExtensionOIDs.orEmpty() +
            certificate.nonCriticalExtensionOIDs.orEmpty()
        return when {
            IAP_AUTH_V3_OID in oids -> 3
            IAP_SW_AUTH_OID in oids -> 4
            MFI4_AUTH_OID in oids -> 5
            else -> 0
        }
    }

    private class DuplicateExtensionException : Exception()

    private fun parseSingleCertificate(bytes: ByteArray): ParsedCertificate {
        require(bytes.size in 1..MAX_CERTIFICATE_BYTES)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
            ?: throw IllegalArgumentException("not an X.509 certificate")
        require(certificate.encoded.contentEquals(bytes)) { "certificate input is not one canonical DER value" }

        val bouncy = BcCertificate.getInstance(ASN1Primitive.fromByteArray(bytes))
        val extensions = bouncy.tbsCertificate.extensions
        val extensionOids = extensions?.extensionOIDs?.toList().orEmpty()
            .map { it.id }
            .toSet()
        if (extensionOids.size != extensions?.extensionOIDs?.toList().orEmpty().size)
            throw DuplicateExtensionException()
        return ParsedCertificate(certificate, extensionOids)
    }

    private fun authorityKeyIdMatches(child: X509Certificate, issuer: X509Certificate): Boolean {
        val childExtensions = extensions(child)
        val authority = childExtensions?.let { AuthorityKeyIdentifier.fromExtensions(it) } ?: return true
        val authorityKeyId = authority.keyIdentifier ?: return true
        val issuerExtensions = extensions(issuer) ?: return false
        val subjectKeyId = SubjectKeyIdentifier.fromExtensions(issuerExtensions)?.keyIdentifier ?: return false
        return MessageDigest.isEqual(authorityKeyId, subjectKeyId)
    }

    private fun extensions(certificate: X509Certificate): Extensions? =
        BcCertificate.getInstance(ASN1Primitive.fromByteArray(certificate.encoded))
            .tbsCertificate.extensions

    private fun allowsCertificateSigning(certificate: X509Certificate): Boolean {
        val usage = certificate.keyUsage ?: return true
        return usage.size > 5 && usage[5]
    }

    private fun hasUnsupportedCriticalExtension(certificate: ParsedCertificate): Boolean =
        certificate.certificate.criticalExtensionOIDs.orEmpty().any { it !in KNOWN_CRITICAL_EXTENSIONS }

    private fun isWeakKey(certificate: X509Certificate): Boolean = when (val key = certificate.publicKey) {
        is ECPublicKey -> key.params.order.bitLength() < 256
        is RSAPublicKey -> key.modulus.bitLength() < 2048
        else -> false
    }

    private fun isWeakSignature(certificate: X509Certificate): Boolean {
        val name = certificate.sigAlgName.uppercase(Locale.ROOT)
        return "MD2" in name || "MD4" in name || "MD5" in name
    }

    private val KNOWN_CRITICAL_EXTENSIONS = setOf(
        "2.5.29.15",  // keyUsage
        "2.5.29.17",  // subjectAltName
        "2.5.29.19",  // basicConstraints
        "2.5.29.30",  // nameConstraints
        "2.5.29.31",  // cRLDistributionPoints
        "2.5.29.32",  // certificatePolicies
        "2.5.29.36",  // policyConstraints
        "2.5.29.37",  // extendedKeyUsage
        "2.5.29.54",  // inhibitAnyPolicy
        "1.3.6.1.5.5.7.1.1",   // authorityInfoAccess
        "1.3.6.1.5.5.7.1.11",  // subjectInfoAccess
        IAP_AUTH_V3_OID,
        IAP_SW_AUTH_OID,
        MFI4_AUTH_OID,
    )
}
