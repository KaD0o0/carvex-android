package io.github.iamr0s.carvex.carplay.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleBasicAttestationTrustTest {
    private val now = Date(1_767_225_600_000L) // 2026-01-01T00:00:00Z

    @Test fun productionRootMatchesFirmwareSha256(){
        assertEquals(
            AppleBasicAttestationTrust.rootSha256Hex,
            MessageDigest.getInstance("SHA-256").digest(AppleBasicAttestationTrust.root.encoded)
                .joinToString(""){"%02x".format(it.toInt() and 255)},
        )
        assertTrue(AppleBasicAttestationTrust.root.subjectX500Principal.name
            .contains("Basic Attestation User Root CA"))
    }

    @Test fun exactThreeCertificateUserChainVerifies(){
        val result=verify(VALID_LEAF,INTERMEDIATE,ROOT)
        assertTrue(result is AppleBasicAttestationTrust.Result.Verified)
        val verified=result as AppleBasicAttestationTrust.Result.Verified
        assertEquals(3,verified.authVersion)
        assertEquals(AccessoryAuth.ChallengeSchema.AA_P256_SHA256_DIGEST_V1,
            AccessoryAuth.schema(verified.leaf,verified.authVersion))
    }

    @Test fun expiredIntermediateDoesNotRejectValidLeaf(){
        val result=verify(LEAF_UNDER_EXPIRED_INTERMEDIATE,EXPIRED_INTERMEDIATE,ROOT)
        assertTrue(result.toString(),
            result is AppleBasicAttestationTrust.Result.Verified)
    }

    @Test fun expiredLeafIsRejected(){
        assertEquals(AppleBasicAttestationTrust.Reason.LEAF_TIME_INVALID,
            reject(EXPIRED_LEAF,INTERMEDIATE,ROOT))
    }

    @Test fun rootSha256MismatchIsRejected(){
        val rootBytes=decode(ROOT)
        assertEquals(
            AppleBasicAttestationTrust.Reason.ROOT_HASH_MISMATCH,
            (AppleBasicAttestationTrust.verifyWithRoot(
                decode(VALID_LEAF),decode(INTERMEDIATE),rootBytes,ByteArray(32){1},now,
            ) as AppleBasicAttestationTrust.Result.Rejected).reason,
        )
    }

    @Test fun publicEntryAlwaysUsesFirmwareUserRoot(){
        assertEquals(
            AppleBasicAttestationTrust.Reason.ISSUER_LINKAGE_INVALID,
            (AppleBasicAttestationTrust.verify(decode(VALID_LEAF),decode(INTERMEDIATE),now)
                as AppleBasicAttestationTrust.Result.Rejected).reason,
        )
    }

    @Test fun leafWithForeignSigningKeyIsRejected(){
        val bad=decode(VALID_LEAF).also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
        assertEquals(AppleBasicAttestationTrust.Reason.SIGNATURE_INVALID,
            rejectBytes(bad,INTERMEDIATE,ROOT))
    }

    @Test fun malformedOrMissingMaterialIsRejected(){
        assertEquals(AppleBasicAttestationTrust.Reason.NO_MATERIAL,
            reject(null,INTERMEDIATE,ROOT))
        assertEquals(AppleBasicAttestationTrust.Reason.MALFORMED_CERTIFICATE,
            rejectBytes(byteArrayOf(1,2,3),INTERMEDIATE,ROOT))
    }

    @Test fun authVersionZeroUsesMessageX962Sha256Signature(){
        val leaf=parse(VALID_LEAF)
        val schema=AccessoryAuth.schema(leaf,0)
        assertEquals(AccessoryAuth.ChallengeSchema.AA_P256_SHA256_MESSAGE_X962,schema)
        val challenge=ByteArray(32){it.toByte()}
        val key=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(LEAF_KEY)))
        val signature=Signature.getInstance("SHA256withECDSA").run{
            initSign(key);update(challenge);sign()
        }
        assertTrue(AccessoryAuth.verifyChallenge(leaf,challenge,signature,schema))
    }

    private fun verify(leaf:String,intermediate:String,root:String)=
        AppleBasicAttestationTrust.verifyWithRoot(
            decode(leaf),decode(intermediate),decode(root),
            MessageDigest.getInstance("SHA-256").digest(decode(root)),now,
        )

    private fun reject(leaf:String?,intermediate:String?,root:String?)=
        (verifyNullable(leaf,intermediate,root) as AppleBasicAttestationTrust.Result.Rejected).reason
    private fun rejectBytes(leaf:ByteArray,intermediate:String,root:String)=
        (AppleBasicAttestationTrust.verifyWithRoot(
            leaf,decode(intermediate),decode(root),
            MessageDigest.getInstance("SHA-256").digest(decode(ROOT)),now,
        ) as AppleBasicAttestationTrust.Result.Rejected).reason

    private fun verifyNullable(leaf:String?,intermediate:String?,root:String?) =
        AppleBasicAttestationTrust.verifyWithRoot(
            leaf?.let(::decode),intermediate?.let(::decode),root?.let(::decode),
            MessageDigest.getInstance("SHA-256").digest(decode(ROOT)),now,
        )

    private fun parse(base64:String) =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(decode(base64).inputStream()) as java.security.cert.X509Certificate

    private fun decode(value:String)=Base64.getDecoder().decode(value)

    private companion object {
        const val ROOT="MIIB5zCCAW2gAwIBAgIUd4U2QHXqOdhZDhkbcZFiWgpjtqswCgYIKoZIzj0EAwMwMzEeMBwGA1UEAwwVVGVzdCBCQUEgVXNlciBSb290IENBMREwDwYDVQQKDAhUZXN0IEJBQTAeFw0yNTAxMDEwMDAwMDBaFw0zNTAxMDEwMDAwMDBaMDMxHjAcBgNVBAMMFVRlc3QgQkFBIFVzZXIgUm9vdCBDQTERMA8GA1UECgwIVGVzdCBCQUEwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAASBsORYfCS22savr8bQKVrVxYwHjHpcI0srFwHYEE97SXLtz2hdafAwGQzHACPyv/PBDFBJstG8lxM0GdZj+i89IVYLvYmHkffr2OVlNqwNeXqeJI4WbEWKXntIWZg23VmjQjBAMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMB0GA1UdDgQWBBSTOyYd4Kcel5JuVCdo2M5VW9L+jTAKBggqhkjOPQQDAwNoADBlAjEA02G53CdezD2WkEiHTtacRRSlNVphHX0yhNFh5OtcpoKl8zLC+lujWjgb9XdDVEO6AjANAyR6HoBFZiWUM+btuf/ArKtB9bNHmA54CIl3cHi8+mfGJ434Gp9tiD4/mKUvQrA="
        const val INTERMEDIATE="MIICBDCCAYmgAwIBAgIUYenFS5qbQZNfBCT9KCKSjkyBHuowCgYIKoZIzj0EAwMwMzEeMBwGA1UEAwwVVGVzdCBCQUEgVXNlciBSb290IENBMREwDwYDVQQKDAhUZXN0IEJBQTAeFw0yNTAxMDEwMDAwMDBaFw0zNTAxMDEwMDAwMDBaMC4xGTAXBgNVBAMMEFRlc3QgQkFBIFVzZXIgQ0ExETAPBgNVBAoMCFRlc3QgQkFBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEpkfWpTnEhfMoADbW24n7nicTfBC06qJXvGmr11Q9I4Xq5yUhf5ocVHWg9MefH17scZvTZbQgt8lHXMof5F3/b7REx32qhcpRCbg5zRs1UUdYIteu+Gpa7Nj3nL5rKXgwo2MwYTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUARVzVOivnqeQiAL9SRIHq0zwddUwHwYDVR0jBBgwFoAUkzsmHeCnHpeSblQnaNjOVVvS/o0wCgYIKoZIzj0EAwMDaQAwZgIxAOQCPQZGlQdN/Bls++VqmJSwbH55J+H8fbuMdJVsG5dtRlekQXsFxIjp0vS9qrO7hQIxAJNQvc7ZPQyX6i/GJj5DM2jEx3i26oKb4RMm36MQp0+eYfQ8U+zkV+pjHAIV27Xu+g=="
        const val VALID_LEAF="MIIB7DCCAXKgAwIBAgIUTi0YHrGqXTRX4lI10OQN82J0Ii0wCgYIKoZIzj0EAwIwLjEZMBcGA1UEAwwQVGVzdCBCQUEgVXNlciBDQTERMA8GA1UECgwIVGVzdCBCQUEwHhcNMjUwMTAxMDAwMDAwWhcNMzUwMTAxMDAwMDAwWjArMRYwFAYDVQQDDA1UZXN0IEJBQSBMZWFmMREwDwYDVQQKDAhUZXN0IEJBQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABNeM0tqS417iFPhqymlxLyW7Y+8yRx+sfMKHW5f7+LyXXOXCKQrQM89Ne0lQz+G2wtFnr2JUlpcBAop4SpzRDD2jcTBvMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQWBBQZ8tdQAtHWjRI42UdKA6K6jBT7dDAfBgNVHSMEGDAWgBQBFXNU6K+ep5CIAv1JEgerTPB11TAPBgkqhkiG92NkBiQEAgUAMAoGCCqGSM49BAMCA2gAMGUCMQDZQbFoZhwapzHdiUU743yg8n0uCb0KHdLhenfml3inc/iQ7Vvm7L93TfPlgcrwYzsCMBSpgNgBdZDo0eSxqK6p671cBWJSBHEsd5ec93B0qCrtRLp/hlX9pkPX6amUSrdcSg=="
        const val EXPIRED_INTERMEDIATE="MIICBjCCAYygAwIBAgIUIGo+xaIy369x5iotawtf6o/aMb8wCgYIKoZIzj0EAwMwMzEeMBwGA1UEAwwVVGVzdCBCQUEgVXNlciBSb290IENBMREwDwYDVQQKDAhUZXN0IEJBQTAeFw0yMDAxMDEwMDAwMDBaFw0yMTAxMDEwMDAwMDBaMDExHDAaBgNVBAMME0V4cGlyZWQgQkFBIFVzZXIgQ0ExETAPBgNVBAoMCFRlc3QgQkFBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE4Oeq+meZaPx9bcRnKq6eeQ4T7FhwWKyrConO5jpXh2lsw0sNnnA5LRgiayHFkTsBHzHwS5vHUbo7UwB1gSWQ8f/6daakthJxeFoaTYxo3HUJ+cIT7MGir5/V4GtKCZN7o2MwYTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQUw3RYnXZxPZIzlTKhk2evLRhfAiMwHwYDVR0jBBgwFoAUkzsmHeCnHpeSblQnaNjOVVvS/o0wCgYIKoZIzj0EAwMDaAAwZQIxAK52xFyuk+FeKwPZzcczjUz3qIiQVwPFekxGGpFata4imtB7pmpGQrsHZ59MII+y4AIwSueOq2YT5bS5VzqLYBR76bYcJORYuk2rIWov/7523vV0UEzERtpwqcA79m2HSZqq"
        const val LEAF_UNDER_EXPIRED_INTERMEDIATE="MIIB9zCCAX2gAwIBAgIUXpo5EZ1zIJyrqriYd0E5K2mwklEwCgYIKoZIzj0EAwIwMTEcMBoGA1UEAwwTRXhwaXJlZCBCQUEgVXNlciBDQTERMA8GA1UECgwIVGVzdCBCQUEwHhcNMjUwMTAxMDAwMDAwWhcNMzUwMTAxMDAwMDAwWjAzMR4wHAYDVQQDDBVMZWFmIFVuZGVyIEV4cGlyZWQgQ0ExETAPBgNVBAoMCFRlc3QgQkFBMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEoQ1DuZ6df7oWI778zVfUR9DfL1LbNP2jZCKxlP79oQdCTp0sFHDyu3/xTkiIqrADEv+FRGGKtfu12DZloQg8DKNxMG8wDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwHQYDVR0OBBYEFEeo2DeQ9OIQlTFacnRK6w3l+eOqMB8GA1UdIwQYMBaAFMN0WJ12cT2SM5UyoZNnry0YXwIjMA8GCSqGSIb3Y2QGJAQCBQAwCgYIKoZIzj0EAwIDaAAwZQIwZzbZ5tMHVOJiD/gtqUyfE9hJ598ZtWX5wvafqUs3aiGnscisGwcztR1dBX89SR3lAjEApvyYRxiXk7RVMFwUzRep16SqR5yTXHK0MKRLFDzI3Aiv3oxxz8Yfx+fedhAZRn6J"
        const val EXPIRED_LEAF="MIIB8DCCAXWgAwIBAgIUODbIs3XiKUgPXsJQXZ3Z2tvKNTwwCgYIKoZIzj0EAwIwLjEZMBcGA1UEAwwQVGVzdCBCQUEgVXNlciBDQTERMA8GA1UECgwIVGVzdCBCQUEwHhcNMjAwMTAxMDAwMDAwWhcNMjEwMTAxMDAwMDAwWjAuMRkwFwYDVQQDDBBFeHBpcmVkIEJBQSBMZWFmMREwDwYDVQQKDAhUZXN0IEJBQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDpVhJnqu1DHzJlOf87hzVWF8dt+T3tGLY2UYceRjBrMCsdOhw82M/M+tmD/W34yVnEeTr/JF5kEdlg6VBXH31OjcTBvMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMB0GA1UdDgQWBBRWrbYEk3YRliP9zdsamTmGSdluyDAfBgNVHSMEGDAWgBQBFXNU6K+ep5CIAv1JEgerTPB11TAPBgkqhkiG92NkBiQEAgUAMAoGCCqGSM49BAMCA2kAMGYCMQDbOeT0vS/42QhzavzhQ0lA5XWlK4m9kmTcgcDVaAvnZ+hSll2TWq+B0C4WRo1/VDkCMQCS+02tTXuC30apBWXdTASZMKzz30lTvhZGNzJ5bLmTMyp0xkajZP2/H8alQHzNk9A="
        const val LEAF_KEY="MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgetLZY3wKpDKt8lPnGb9IsIhY/mRW0ahHb1B770grfCOhRANCAATXjNLakuNe4hT4asppcS8lu2PvMkcfrHzCh1uX+/i8l1zlwikK0DPPTXtJUM/htsLRZ69iVJaXAQKKeEqc0Qw9"
    }
}
