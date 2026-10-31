package io.github.iamr0s.carvex.carplay.core
import java.security.cert.*
import java.util.Date

enum class AuthLayer { IAP2_AA, AIRPLAY_SAP }
/** Implemented only by an independently authenticated support package, never by peer certificates. */
interface AuthTrustPolicy {
    val layer: AuthLayer
    val policyId: String
    val revision: Long
    val anchors: Set<TrustAnchor>
    val synthetic: Boolean
    /** Must check actual extension bytes and purpose, not just recognize an OID. */
    val criticalExtensionDecoders: Map<String, (X509Certificate, ByteArray) -> Boolean>
    fun acceptsPurpose(leaf: X509Certificate, schema: String): Boolean
    fun revoked(certificate: X509Certificate): Boolean
}
class AuthTrustResult internal constructor(
    val layer: AuthLayer, val policyId: String, val revision: Long, val synthetic: Boolean,
    val binding: SecurityBinding,
)

/** Container codec and leaf-selection rules belong to the explicit layer schema. */
object AuthTrustVerifier {
    fun verify(policy: AuthTrustPolicy?, layer: AuthLayer, schema: String,
               orderedPath: List<X509Certificate>, trustedTime: Date?, binding: SecurityBinding,
               verifyCurrentTranscript: (X509Certificate) -> Boolean): AuthTrustResult {
        fun fail(reason: SecurityReason): Nothing = securityFailure(reason, layer.name, "trust")
        if (policy == null || policy.anchors.isEmpty()) fail(if (layer == AuthLayer.IAP2_AA)
            SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE else SecurityReason.SAP_TRUST_MATERIAL_UNAVAILABLE)
        if (policy.layer != layer || policy.policyId.isBlank() || policy.revision < 1)
            fail(SecurityReason.TRUST_PURPOSE_UNSUPPORTED)
        if (orderedPath.isEmpty()) fail(SecurityReason.CERT_LEAF_AMBIGUOUS)
        if (trustedTime == null) fail(SecurityReason.TRUST_TIME_UNAVAILABLE)
        val anchors = policy.anchors.toSet()
        val decoders = policy.criticalExtensionDecoders.toMap()
        val leaf = orderedPath.first()
        if (!policy.acceptsPurpose(leaf, schema)) fail(SecurityReason.TRUST_PURPOSE_UNSUPPORTED)
        orderedPath.forEach { cert ->
            try { cert.checkValidity(trustedTime) } catch (e: CertificateException) { fail(SecurityReason.CERT_TIME_INVALID) }
            if (policy.revoked(cert)) fail(SecurityReason.CERT_REVOKED)
            cert.criticalExtensionOIDs.orEmpty().forEach { oid ->
                val decoder = decoders[oid] ?: fail(SecurityReason.CERT_CRITICAL_EXTENSION_UNSUPPORTED)
                if (!runCatching { decoder(cert, cert.getExtensionValue(oid)) }.getOrDefault(false)) fail(SecurityReason.CERT_EXTENSION_INVALID)
            }
        }
        val understood = decoders.keys.toSet()
        val checker = object : PKIXCertPathChecker() {
            override fun init(forward: Boolean) { require(!forward) }
            override fun isForwardCheckingSupported() = false
            override fun getSupportedExtensions() = understood
            override fun check(cert: java.security.cert.Certificate, unresolved: MutableCollection<String>) {
                // Values were validated above; PKIX still enforces path/name/CA constraints.
                unresolved.removeAll(understood)
            }
        }
        try {
            val path = CertificateFactory.getInstance("X.509").generateCertPath(orderedPath)
            val params = PKIXParameters(anchors).apply {
                date = trustedTime; isRevocationEnabled = false // Explicit support-package revocation snapshot above.
                addCertPathChecker(checker)
            }
            CertPathValidator.getInstance("PKIX").validate(path, params)
        } catch (e: java.security.GeneralSecurityException) { fail(SecurityReason.CERT_CHAIN_INVALID) }
        if (!runCatching { verifyCurrentTranscript(leaf) }.getOrDefault(false)) fail(if (layer == AuthLayer.IAP2_AA)
            SecurityReason.AA_SIGNATURE_INVALID else SecurityReason.SAP_SIGNATURE_INVALID)
        return AuthTrustResult(layer, policy.policyId, policy.revision, policy.synthetic, binding)
    }
    fun requireProductionPair(aa: AuthTrustResult, sap: AuthTrustResult, binding: SecurityBinding) {
        if (aa.layer != AuthLayer.IAP2_AA || sap.layer != AuthLayer.AIRPLAY_SAP ||
            aa.synthetic || sap.synthetic || aa.binding != binding || sap.binding != binding)
            securityFailure(SecurityReason.TRUST_PURPOSE_UNSUPPORTED)
    }
}
