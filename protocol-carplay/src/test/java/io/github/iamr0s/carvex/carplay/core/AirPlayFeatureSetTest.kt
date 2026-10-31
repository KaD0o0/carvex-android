package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Synthetic parser vectors, not a real receiver transcript or ARM execution. */
class AirPlayFeatureSetTest {
    private fun parse(text: String) = AirPlayFeatureEvidence.fromBonjour(text, "0x4000000000")

    @Test fun everyBitAcrossTheLow64BoundaryRetainsItsIndex() {
        for (bit in listOf(0, 7, 8, 38, 63, 64, 79, 80, 87, 127)) {
            val bytes = ByteArray(bit / 8 + 1).apply { this[bit / 8] = (1 shl (bit % 8)).toByte() }
            val parsed = parse(Base64.getEncoder().encodeToString(bytes))
            assertFalse(parsed.malformed)
            for (other in 0..128) assertEquals("bit=$bit query=$other", other == bit, parsed.has(other))
            assertEquals(if (bit < 64) 1UL shl bit else 0UL, parsed.set!!.legacyFlags)
            assertFalse(parsed.set.has(-1))
        }
    }

    @Test fun featureBufferIsImmutableAndUsesValueEquality() {
        val original = byteArrayOf(1, 2, 3)
        val set = AirPlayFeatureSet(original)
        original.fill(0)
        set.bytes.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), set.bytes)
        assertEquals(AirPlayFeatureSet(byteArrayOf(1, 2, 3)), set)
        assertEquals(0x030201UL, set.legacyFlags)
    }

    @Test fun legacyConversionRetainsAll64BitsWithoutShiftWrappingAt80() {
        for (flags in listOf(0UL, 1UL, 0x8000000000000000UL, ULong.MAX_VALUE)) {
            val set = AirPlayFeatureSet.fromLegacyFlags(flags)
            assertEquals(flags, set.legacyFlags)
            assertFalse(set.has(64))
            assertFalse(set.has(80))
        }
    }

    @Test fun standardAndUrlSafeAlphabetsMayBeMixed() {
        for (text in listOf("+/8=", "-_8=", "+_8=", "-/8="))
            assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), parse(text).set!!.bytes)
    }

    @Test fun originalDecoderAcceptsExactlyTheSixAsciiWhitespaceCharacters() {
        assertArrayEquals(byteArrayOf(1, 2, 3), parse(" A\tQ\nI\u000bD\u000c\r ").set!!.bytes)
        assertEquals(AirPlayFeatureProblem.UNSUPPORTED_ENCODING, parse("AQ\u00a0==").problem)
        assertEquals(AirPlayFeatureProblem.MALFORMED_BASE64, parse("AQ\u0001==").problem)
    }

    @Test fun originalTailAndTerminatorToleranceIsNotJavaBase64Grammar() {
        val vectors = mapOf(
            "" to byteArrayOf(), "A" to byteArrayOf(), "=" to byteArrayOf(),
            "AQ" to byteArrayOf(1), "AQI" to byteArrayOf(1, 2), "AQID" to byteArrayOf(1, 2, 3),
            "AB" to byteArrayOf(0), "AQJ" to byteArrayOf(1, 2),
            "AQ=!?ignored" to byteArrayOf(1), "AQID=!?" to byteArrayOf(1, 2, 3),
            "AQ\u0000!?" to byteArrayOf(1), "AQIDA" to byteArrayOf(1, 2, 3))
        for ((text, expected) in vectors) assertArrayEquals(text, expected, parse(text).set!!.bytes)
        // ASCII conversion precedes both strlen and '=' termination.
        assertEquals(AirPlayFeatureProblem.UNSUPPORTED_ENCODING, parse("AQ=é").problem)
    }

    @Test fun invalidAlphabetBeforeTerminationNeverBecomesLegacyFeatures() {
        for (text in listOf("!", "AQ!", "AA.A", "0x40,0x0")) {
            val evidence = parse(text)
            assertEquals(AirPlayFeatureSource.BONJOUR_FEX, evidence.source)
            assertEquals(AirPlayFeatureProblem.MALFORMED_BASE64, evidence.problem)
            assertTrue(evidence.malformed)
            assertNull(evidence.set)
            assertNull(evidence.has(38))
        }
    }

    @Test fun fexPresenceWinsEvenWhenEmptyOrOnlyHighBitsAreSet() {
        assertEquals(0UL, parse("").set!!.legacyFlags)
        val high = ByteArray(11).apply { this[10] = 1 }
        val evidence = parse(Base64.getEncoder().encodeToString(high))
        assertEquals(true, evidence.has(80))
        assertEquals(false, evidence.has(38))
        assertEquals(0UL, evidence.set!!.legacyFlags)
    }

    @Test fun missingMalformedAndLegacyZeroRemainDistinct() {
        val missing = AirPlayFeatureEvidence.fromBonjour(null, null)
        val malformed = AirPlayFeatureEvidence.fromBonjour(null, "not-hex")
        val legacy = AirPlayFeatureEvidence.fromBonjour(null, "0x0,0x0")
        assertEquals(AirPlayFeatureSource.MISSING, missing.source)
        assertFalse(missing.malformed)
        assertNull(missing.has(38))
        assertEquals(AirPlayFeatureProblem.MALFORMED_LEGACY_FLAGS, malformed.problem)
        assertTrue(malformed.malformed)
        assertEquals(AirPlayFeatureSource.LEGACY_FLAGS, legacy.source)
        assertEquals(false, legacy.has(38))
        assertEquals(3, listOf(missing, malformed, legacy).map { it.diagnostic() }.toSet().size)
    }

    @Test fun localLengthLimitIsExplicitAndDiagnosticsDoNotEchoUntrustedInput() {
        val text = "A".repeat(AirPlayFeatureEvidence.MAX_EXTENDED_TEXT_LENGTH + 1)
        val evidence = parse(text)
        assertEquals(AirPlayFeatureProblem.MALFORMED_LENGTH, evidence.problem)
        assertFalse(evidence.diagnostic().contains(text))
        assertNotNull(parse(text.dropLast(1)).set)
    }
}
