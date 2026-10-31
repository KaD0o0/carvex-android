package io.github.iamr0s.carvex.carplay.core

import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.SignedData
import org.bouncycastle.asn1.x509.Certificate

/**
 * 配件证书链的信任锚（Apple Accessories Certification Authority）。
 *
 * 用途：车机在 `aa01` 里出示叶证书（参数 0）与链（参数 2），iPhone 用该体系验证"这是 Apple
 * 授权配件"。此前本实现只用叶证书公钥验挑战签名（日志里的 "root trust NOT verified"），
 * 这里补上链校验。
 *
 * 边界（第 03 章 §7 仍是 `[OPEN]`）：链结构、吊销（CRL/OCSP）、以及 mode 1 的完整语义未闭合。
 * 因此本类只做"能否链到已知根"的判定，且**不**据此拒绝会话——结果仅用于日志/审计。
 */
object AppleAccessoryTrust {
    /** 24A446 MFi4 production root: Apple Accessory Root CA - 00000001. */
    private const val ROOT_MFI4_00000001 = """
-----BEGIN CERTIFICATE-----
MIIB9DCCAZqgAwIBAgIUfK8gpf3d6f7acICzvf5MBWnAVEYwCgYIKoZIzj0EAwIw
VzErMCkGA1UEAwwiQXBwbGUgQWNjZXNzb3J5IFJvb3QgQ0EgLSAwMDAwMDAwMTET
MBEGA1UECgwKQXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAgFw0yMDA5
MTYxODI5MzRaGA8yMDUwMDkxNDAwMDAwMFowVzErMCkGA1UEAwwiQXBwbGUgQWNj
ZXNzb3J5IFJvb3QgQ0EgLSAwMDAwMDAwMTETMBEGA1UECgwKQXBwbGUgSW5jLjET
MBEGA1UECAwKQ2FsaWZvcm5pYTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABMGo
QSB6dmrLAhjkTUVAoAC1RwaOmEmEk7SYEnnm6aBw9HZZQt5pkncuTXxvL6NDvx4D
8Wn3frxp+UOTgEalb0ajQjBAMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFAZD
Zup+SdTmXls49aokHuK+fHKjMA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAgNI
ADBFAiEA4HcCYv5+W+1/HOy1v1cYVozNEW4+2WvR84Japg8rabkCIDP9zynWiR60
O06J60wrt0rQfBOPnoeY1+ydbP2g8Tiy
-----END CERTIFICATE-----
"""

    enum class Result { VERIFIED, NOT_A_CHAIN, UNKNOWN_ISSUER, INVALID, NO_MATERIAL }

    val roots: List<X509Certificate> by lazy {
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(ROOT_MFI4_00000001.trimIndent().toByteArray()))
            .filterIsInstance<X509Certificate>()
    }

    /**
     * 本地审计解析器。24A446 的部分 MFAA 公共证书路径会经
     * copyParsedCertificateChainInfo: 进入 CTParseAccessoryCerts；但
     * AA01 mode=1 的 BAA 路径由 validateCertificateChain:type:0 直接构造
     * SecCertificate，参数 0/2 不是这条 CMS 路径。这里保留 CMS 优先与 DER
     * 回退仅用于审计旧样本，结果不参与生产认证放行。
     */
    fun parseChain(bytes: ByteArray?): List<X509Certificate> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val cms = runCatching { parseCmsCertificates(bytes) }.getOrNull()
        if (!cms.isNullOrEmpty()) return cms
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
        }.getOrDefault(emptyList())
    }

    /** Enumerate the certificate choices in a CMS SignedData container without asserting trust. */
    private fun parseCmsCertificates(bytes: ByteArray): List<X509Certificate> {
        val info = ContentInfo.getInstance(ASN1Primitive.fromByteArray(bytes))
        require(info.contentType == PKCSObjectIdentifiers.signedData)
        val signed = SignedData.getInstance(info.content)
        val set = signed.certificates ?: return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        return set.objects.asSequence().mapNotNull { value ->
            val primitive = (value as? ASN1Encodable)?.toASN1Primitive()
                ?: return@mapNotNull null
            val certificate = if (primitive is ASN1TaggedObject) {
                if (primitive.tagNo != 0) return@mapNotNull null
                Certificate.getInstance(primitive.baseObject)
            } else {
                // CertificateChoices permits the ordinary X.509 Certificate choice
                // without an extra tag; attribute/other choices remain ignored.
                runCatching { Certificate.getInstance(primitive) }.getOrNull()
                    ?: return@mapNotNull null
            }
            factory.generateCertificate(ByteArrayInputStream(certificate.encoded)) as? X509Certificate
        }.toList()
    }

    /**
     * 用已知根验证 `leaf` 加上 `chain` 能否构成一条合法路径。
     * 关闭吊销检查（文档第 03 章 §7 把吊销策略列为 `[OPEN]`）；其余（签名、有效期、基本约束）由 PKIX 处理。
     */
    fun verify(leaf: X509Certificate, chain: List<X509Certificate>, now: java.util.Date = java.util.Date()): Result {
        if (roots.isEmpty()) return Result.NO_MATERIAL
        val candidates = (listOf(leaf) + chain).distinctBy { it.encoded.contentHashCode() }
        if (candidates.size < 2) return Result.NOT_A_CHAIN
        val anchors = roots.map { TrustAnchor(it, null) }.toSet()
        val pool = candidates.filterNot { c -> roots.any { it.encoded.contentEquals(c.encoded) } }
        if (pool.isEmpty()) return Result.NOT_A_CHAIN
        return try {
            val path = CertificateFactory.getInstance("X.509").generateCertPath(pool)
            val parameters = PKIXParameters(anchors).apply {
                isRevocationEnabled = false
                date = now
            }
            CertPathValidator.getInstance("PKIX").validate(path, parameters)
            Result.VERIFIED
        } catch (t: java.security.cert.CertPathValidatorException) {
            if (t.reason == java.security.cert.CertPathValidatorException.BasicReason.UNDETERMINED_REVOCATION_STATUS)
                Result.VERIFIED
            else if (t.message?.contains("issuer", ignoreCase = true) == true) Result.UNKNOWN_ISSUER
            else Result.INVALID
        } catch (t: java.security.cert.CertificateException) {
            Result.INVALID
        } catch (t: java.security.InvalidAlgorithmParameterException) {
            Result.NO_MATERIAL
        }
    }
}
