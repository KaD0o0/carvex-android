package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test

class StartSessionSyntaxTest {
    private fun parse(vararg fields: Pair<Int, ByteArray>) = StartSessionSyntax.normalize(IapFields(fields.toList()))
    private fun group(vararg fields: Pair<Int, ByteArray>) = IapWire.fields(fields.toList())
    private fun fails(path: String, problem: StartSessionProblem, vararg fields: Pair<Int, ByteArray>) {
        val error = assertThrows(StartSessionConfigurationException::class.java) { parse(*fields) }
        assertEquals(path, error.fieldPath)
        assertEquals(problem, error.problem)
    }

    @Test fun emptyAndPartialRequestsAreSyntaxValidWithoutInventingDefaults() {
        assertTrue(parse().isEmpty())
        val parsed = parse(1 to group(), 8 to byteArrayOf(0))
        assertNotNull(parsed[1]); assertNull(parsed[2]); assertNull(parsed[3])
        assertArrayEquals(byteArrayOf(0), parsed[8])
    }

    @Test fun repeatedGroupsContinueScalarSlotsAndAppendAddresses() {
        val parsed = parse(
            1 to group(0 to IapWire.cstring("first"), 1 to IapWire.cstring("password1"), 3 to IapWire.cstring("192.0.2.1")),
            1 to group(0 to IapWire.cstring("last"), 3 to IapWire.cstring("192.0.2.2")),
            1 to group(),
            0 to group(0 to IapWire.cstring("usb1")), 0 to group(0 to IapWire.cstring("usb2")),
            7 to group(0 to IapWire.cstring("asset")), 7 to group(1 to IapWire.be32(7)))
        val wifi = IapWire.parseFieldList(parsed[1]!!)
        assertEquals("last", IapWire.text(wifi[0]!!))
        assertEquals("password1", IapWire.text(wifi[1]!!))
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), wifi.ordered.filter { it.first == 3 }.map { IapWire.text(it.second) })
        assertEquals(2, IapWire.parseFieldList(parsed[0]!!).ordered.size)
        assertEquals(2, IapWire.parseFieldList(parsed[7]!!).ordered.size)
    }

    @Test fun allOuterScalarsUseLastValidValue() {
        val parsed = parse(2 to IapWire.be32(1), 2 to IapWire.be32(2),
            3 to IapWire.cstring("first"), 3 to IapWire.cstring("last"),
            4 to IapWire.cstring("key1"), 4 to IapWire.cstring("key2"),
            5 to IapWire.cstring("version1"), 5 to IapWire.cstring("version2"),
            6 to IapWire.cstring("sdk1"), 6 to IapWire.cstring("sdk2"),
            8 to byteArrayOf(1), 8 to byteArrayOf(0))
        assertArrayEquals(IapWire.be32(2), parsed[2])
        for ((id, expected) in listOf(3 to "last", 4 to "key2", 5 to "version2", 6 to "sdk2"))
            assertEquals(expected, IapWire.text(parsed[id]!!))
        assertArrayEquals(byteArrayOf(0), parsed[8])
    }

    @Test fun everyKnownStringRequiresFinalNulEvenInUnusedUsbOrAssetGroups() {
        val malformed = byteArrayOf(65, 0, 66)
        for (id in listOf(3, 4, 5, 6)) fails(id.toString(), StartSessionProblem.INVALID_TEXT, id to malformed)
        for ((outer, leaf) in listOf(0 to 0, 1 to 0, 1 to 1, 1 to 3, 7 to 0))
            fails("$outer.$leaf", StartSessionProblem.INVALID_TEXT, outer to group(leaf to malformed))
    }

    @Test fun emptyAndEmbeddedNulStringsRemainValidAtSyntaxLayer() {
        val parsed = parse(3 to byteArrayOf(0), 4 to byteArrayOf(65, 0, 66, 0))
        assertEquals("", IapWire.text(parsed[3]!!))
        assertEquals("A", IapWire.text(parsed[4]!!))
    }

    @Test fun allIntegerWidthsAndBooleanRangeAreChecked() {
        for (raw in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(8))) {
            fails("2", StartSessionProblem.INVALID_WIDTH, 2 to raw)
            fails("7.1", StartSessionProblem.INVALID_WIDTH, 7 to group(1 to raw))
        }
        for (raw in listOf(byteArrayOf(), byteArrayOf(0, 1))) {
            fails("8", StartSessionProblem.INVALID_WIDTH, 8 to raw)
            for (leaf in listOf(2, 4)) fails("1.$leaf", StartSessionProblem.INVALID_WIDTH, 1 to group(leaf to raw))
        }
        fails("8", StartSessionProblem.INVALID_BOOLEAN, 8 to byteArrayOf(2))
        fails("8", StartSessionProblem.INVALID_BOOLEAN, 8 to byteArrayOf(255.toByte()))
    }

    @Test fun syntaxDoesNotInventPortAddressKeyOrSecurityPolicies() {
        val parsed = parse(2 to IapWire.be32(0xffffffffL), 4 to IapWire.cstring("not-hex"),
            1 to group(3 to IapWire.cstring("not-an-ip"), 4 to byteArrayOf(255.toByte())))
        assertArrayEquals(IapWire.be32(0xffffffffL), parsed[2])
        assertEquals("not-hex", IapWire.text(parsed[4]!!))
    }

    @Test fun firstMalformedKnownValueWinsRegardlessOfLaterReplacementOrMissingFields() {
        fails("1.2", StartSessionProblem.INVALID_WIDTH,
            1 to group(2 to byteArrayOf(0, 1)), 1 to group(2 to byteArrayOf(1)), 8 to byteArrayOf(2))
        fails("3", StartSessionProblem.INVALID_TEXT, 3 to byteArrayOf(65), 3 to IapWire.cstring("valid"))
        fails("7.1", StartSessionProblem.INVALID_WIDTH, 7 to group(1 to byteArrayOf(0)), 5 to byteArrayOf(1))
    }

    @Test fun unknownFieldsAreNotRecursivelyInterpreted() {
        val parsed = parse(99 to byteArrayOf(1), 1 to group(99 to byteArrayOf(1)), 7 to group(3 to byteArrayOf(2)))
        assertNull(parsed[99])
        assertTrue(IapWire.parseFieldList(parsed[1]!!).isEmpty())
        assertTrue(IapWire.parseFieldList(parsed[7]!!).isEmpty())
    }

    @Test fun malformedKnownGroupIsNotAValidPartialRequest() {
        for (id in listOf(0, 1, 7)) fails(id.toString(), StartSessionProblem.INVALID_GROUP, id to byteArrayOf(0, 5, 0, 0))
    }

    @Test fun normalizedBytesDoNotAliasInput() {
        val raw = IapWire.cstring("original")
        val parsed = parse(3 to raw)
        raw.fill(1)
        assertEquals("original", IapWire.text(parsed[3]!!))
    }
}
