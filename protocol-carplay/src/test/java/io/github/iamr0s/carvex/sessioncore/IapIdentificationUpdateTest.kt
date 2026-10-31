package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test

class IapIdentificationUpdateTest {
    private fun initial() = IdentifyInfo(
        name = "name", modelIdentifier = "model", manufacturer = "maker",
        serialNumber = "serial", firmwareVersion = "firmware", hardwareVersion = "hardware",
        accessorySends = listOf(MessageId(0x57, 3)),
        accessoryReceives = listOf(MessageId(0x57, 2)),
        rawParameters = listOf(RawParameter(0, IapWire.cstring("name"))),
    )

    private fun identityValues(info: IdentifyInfo) = listOf(info.name, info.modelIdentifier,
        info.manufacturer, info.serialNumber, info.firmwareVersion, info.hardwareVersion)

    @Test fun eachIdentityFieldCanBeUpdatedWithoutRequiringOtherFields() {
        val original = initial()
        for (id in 0..5) {
            val result = IapIdentificationUpdate.apply(original,
                IapFields(listOf(id to IapWire.cstring("replacement-$id"))))
            val expected = identityValues(original).toMutableList().apply { this[id] = "replacement-$id" }
            assertEquals("field=$id", expected, identityValues(result))
            assertSame(original.accessorySends, result.accessorySends)
            assertSame(original.accessoryReceives, result.accessoryReceives)
            assertSame(original.rawParameters, result.rawParameters)
        }
    }

    @Test fun missingNullFieldsStayNullWhileExplicitEmptyStringsReplaceOldValues() {
        val original = initial().copy(modelIdentifier = null)
        val result = IapIdentificationUpdate.apply(original, IapFields(listOf(0 to byteArrayOf(0))))
        assertEquals("", result.name)
        assertNull(result.modelIdentifier)
        assertEquals(original.manufacturer, result.manufacturer)
    }

    @Test fun emptyUpdatePreservesIdentityAndInitialDeclarationEvidence() {
        val original = initial()
        assertEquals(original, IapIdentificationUpdate.apply(original, IapFields(emptyList())))
    }

    @Test fun repeatedValidStringsUseLastOccurrenceInWireOrder() {
        val result = IapIdentificationUpdate.apply(initial(), IapFields(listOf(
            0 to IapWire.cstring("first"), 2 to IapWire.cstring("other"),
            0 to IapWire.cstring("last"), 2 to byteArrayOf(0),
        )))
        assertEquals("last", result.name)
        assertEquals("", result.manufacturer)
    }

    @Test fun invalidKnownFieldStopsBeforeAValidRepeatCanMaskIt() {
        val error = assertThrows(IdentificationParameterException::class.java) {
            IapIdentificationUpdate.apply(initial(), IapFields(listOf(
                0 to byteArrayOf(65), 0 to IapWire.cstring("later valid"),
            )))
        }
        assertEquals(0, error.parameterId)
    }

    @Test fun laterInvalidFieldRejectsWholeDeltaWithoutMutatingOriginal() {
        val original = initial()
        val expected = original.copy()
        val error = assertThrows(IdentificationParameterException::class.java) {
            IapIdentificationUpdate.apply(original, IapFields(listOf(
                0 to IapWire.cstring("new"), 5 to byteArrayOf(),
            )))
        }
        assertEquals(5, error.parameterId)
        assertEquals(expected, original)
    }

    @Test fun zeroLengthAndMissingFinalNulAreRejectedForEveryIdentityField() {
        for (id in 0..5) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(65), byteArrayOf(65, 0, 66))) {
                val error = assertThrows(IdentificationParameterException::class.java) {
                    IapIdentificationUpdate.apply(initial(), IapFields(listOf(id to bytes)))
                }
                assertEquals(id, error.parameterId)
            }
        }
    }

    @Test fun middleNulIsAllowedWhenLastByteIsAlsoNul() {
        val result = IapIdentificationUpdate.apply(initial(),
            IapFields(listOf(0 to byteArrayOf(65, 0, 66, 0))))
        assertEquals("A", result.name)
    }

    @Test fun languageMessageDeclarationsPpidAndUnknownUpdateIdsAreIgnored() {
        val original = initial()
        val ignored = listOf(6, 7, 12, 13, 34, 0xffff).flatMap { id ->
            listOf(id to byteArrayOf(), id to byteArrayOf(1), id to byteArrayOf(0xff.toByte()))
        }
        assertEquals(original, IapIdentificationUpdate.apply(original, IapFields(ignored)))
    }

    @Test fun ignoredMalformedFieldsDoNotPreventValidIdentityUpdates() {
        val original = initial()
        val result = IapIdentificationUpdate.apply(original, IapFields(listOf(
            7 to byteArrayOf(0x43), 0 to IapWire.cstring("new"),
            6 to byteArrayOf(0x43), 999 to byteArrayOf(),
        )))
        assertEquals("new", result.name)
        assertEquals(original.accessorySends, result.accessorySends)
        assertEquals(original.accessoryReceives, result.accessoryReceives)
        assertEquals(original.rawParameters, result.rawParameters)
    }
}
