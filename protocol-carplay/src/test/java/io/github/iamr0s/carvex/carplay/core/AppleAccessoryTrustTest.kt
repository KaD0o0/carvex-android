package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAccessoryTrustTest {
    @Test fun rootParsesAsCertificateAuthority(){
        val roots = AppleAccessoryTrust.roots
        assertEquals(1, roots.size)
        val root = roots.single()
        assertTrue(root.subjectX500Principal.name.contains("Apple Accessory Root CA - 00000001"))
        assertEquals(root.subjectX500Principal, root.issuerX500Principal)
        // 自签校验：用自己的公钥验自己的签名（比断言扩展字段更稳，不依赖 provider 的扩展解析）。
        root.verify(root.publicKey)
    }

    @Test fun firmwareMfi4IntermediateChainsToFirmwareRoot(){
        val pem=requireNotNull(javaClass.getResourceAsStream("/firmware-mfi4-accessory-intermediate.pem"))
            .use { it.readBytes() }
        val intermediate=requireNotNull(AppleAccessoryTrust.parseChain(pem).singleOrNull())
        assertEquals(AppleAccessoryTrust.Result.VERIFIED,
            AppleAccessoryTrust.verify(intermediate, AppleAccessoryTrust.roots))
    }

    @Test fun leafAloneIsNotAChain(){
        assertEquals(
            AppleAccessoryTrust.Result.NOT_A_CHAIN,
            AppleAccessoryTrust.verify(AppleAccessoryTrust.roots.single(), emptyList()),
        )
    }

    @Test fun emptyChainMaterialIsTreatedAsAbsent(){
        assertTrue(AppleAccessoryTrust.parseChain(null).isEmpty())
        assertTrue(AppleAccessoryTrust.parseChain(ByteArray(0)).isEmpty())
        assertTrue(AppleAccessoryTrust.parseChain(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test fun cmsSignedDataChainMaterialIsEnumeratedForAudit(){
        val cms=requireNotNull(javaClass.getResourceAsStream("/synthetic-aa-p256.p7b"))
            .use { it.readBytes() }
        val certificates=AppleAccessoryTrust.parseChain(cms)
        assertEquals(1,certificates.size)
        assertTrue(certificates.single().basicConstraints<0)
    }
}
