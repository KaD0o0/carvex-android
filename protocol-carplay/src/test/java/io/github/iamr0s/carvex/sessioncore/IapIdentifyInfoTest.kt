package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test

class IapIdentifyInfoTest {
    // Explicit core-only fixture: not a complete Bluetooth/Power/feature contract.
    private fun core() = (0..5).map { it to IapWire.cstring("identity-$it") } + listOf(
        6 to byteArrayOf(0x57, 3), 7 to byteArrayOf(0x57, 2),
        12 to IapWire.cstring("en"), 13 to IapWire.cstring("en"),
    )
    private fun parse(fields: List<Pair<Int, ByteArray>>) = IapIdentifyInfo.parse(IapFields(fields))
    private fun replace(id: Int, bytes: ByteArray) = core().map { if (it.first == id) id to bytes else it }
    private fun scalar(info: IdentifyInfo, id: Int): String? = when (id) {
        0 -> info.name; 1 -> info.modelIdentifier; 2 -> info.manufacturer
        3 -> info.serialNumber; 4 -> info.firmwareVersion; 5 -> info.hardwareVersion
        12 -> info.currentLanguage; 34 -> info.ppid; else -> error("not a scalar")
    }
    private fun invalid(fields: List<Pair<Int, ByteArray>>, id: Int, rejected: List<Int> = listOf(id)) {
        val error = assertThrows(IdentificationParameterException::class.java) { parse(fields) }
        assertEquals(id, error.parameterId)
        assertEquals(rejected, error.rejectedParameterIds)
    }

    @Test fun coreFieldsAreRetainedWithLanguageAndOptionalPpid() {
        val info = parse(core() + (34 to IapWire.cstring("ppid")))
        for (id in 0..5) assertEquals("identity-$id", scalar(info, id))
        assertEquals("en", info.currentLanguage)
        assertEquals(listOf("en"), info.supportedLanguages)
        assertEquals("ppid", info.ppid)
        assertNull(parse(core()).ppid)
    }

    @Test fun rawFieldsRetainWireOrderDuplicatesAndIndependentBytes() {
        val bytes = byteArrayOf(0x43, 0, 0x43, 0)
        val fields = listOf(7 to bytes) + core() + listOf(7 to bytes, 12 to IapWire.cstring("fr"))
        val info = parse(fields)
        assertEquals(fields.map { it.first }, info.rawParameters.map { it.id })
        assertEquals(listOf(MessageId(0x43, 0)), info.accessoryReceives)
        bytes[0] = 0
        assertEquals(0x43, info.rawParameters.first().value[0].toInt())
        assertEquals(0x43, info.rawParameters[info.rawParameters.size - 2].value[0].toInt())
    }

    @Test fun validRepeatedScalarsReplaceRatherThanRejectOrConcatenate() {
        for (id in (0..5).toList() + listOf(12, 34)) {
            val result = parse(core() + listOf(id to IapWire.cstring("second"), id to IapWire.cstring("last")))
            assertEquals("id=$id", "last", scalar(result, id))
        }
    }

    @Test fun emptyCStringIsAValueNotAnOmissionForEveryScalar() {
        for (id in (0..5).toList() + listOf(12, 34)) {
            assertEquals("id=$id", "", scalar(parse(core() + (id to byteArrayOf(0))), id))
        }
    }

    @Test fun framingChecksLastNulNotTheFirstNul() {
        for (id in (0..5).toList() + listOf(12, 34)) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(65), byteArrayOf(65, 0, 66))) {
                invalid(core() + (id to bytes), id)
            }
            assertEquals("A", scalar(parse(core() + (id to byteArrayOf(65, 0, 66, 0))), id))
        }
    }

    @Test fun firstBadScalarCannotBeMaskedByAValidRepeat() {
        for (id in (0..5).toList() + listOf(12, 34)) {
            invalid(listOf(id to byteArrayOf(65)) + core() + (id to IapWire.cstring("later")), id)
            invalid(core() + listOf(id to IapWire.cstring("first"), id to byteArrayOf(65)), id)
        }
    }

    @Test fun messageListsDeduplicateWithinAnOccurrenceAndKeepDirectionsSeparate() {
        val info = parse(core() + listOf(
            6 to byteArrayOf(0xff.toByte(), 0xff.toByte(), 0, 0, 0xff.toByte(), 0xff.toByte(), 0x43, 1),
            7 to byteArrayOf(0x43, 0, 0x43, 0),
        ))
        assertEquals(listOf(MessageId(255, 255), MessageId(0, 0), MessageId(0x43, 1)), info.accessorySends)
        assertEquals(listOf(MessageId(0x43, 0)), info.accessoryReceives)
    }

    @Test fun repeatedMessageListsReplaceTheWholeSetIncludingWithEmpty() {
        for (id in listOf(6, 7)) {
            val info = parse(core() + listOf(id to byteArrayOf(0x43, 1), id to byteArrayOf(0x50, 0)))
            assertEquals(listOf(MessageId(0x50, 0)), if (id == 6) info.accessorySends else info.accessoryReceives)
            val empty = parse(core() + (id to byteArrayOf()))
            assertTrue((if (id == 6) empty.accessorySends else empty.accessoryReceives).isEmpty())
        }
    }

    @Test fun oddMessageListFailsEvenAfterSuccessOrBeforeLaterValidValue() {
        for (id in listOf(6, 7)) {
            invalid(core() + (id to byteArrayOf(1)), id)
            invalid(listOf(id to byteArrayOf(1)) + core(), id)
            invalid(core() + listOf(id to byteArrayOf(1), id to byteArrayOf()), id)
        }
    }

    @Test fun differentLanguagesAccumulateWhileCurrentLanguageReplaces() {
        val info = parse(core() + listOf(13 to IapWire.cstring("fr"), 12 to IapWire.cstring("fr"),
            13 to IapWire.cstring("zh")))
        assertEquals("fr", info.currentLanguage)
        assertEquals(listOf("en", "fr", "zh"), info.supportedLanguages)
    }

    @Test fun equalLanguageIsRejectedOnceRatherThanDeduplicated() {
        invalid(core() + (13 to IapWire.cstring("en")), 13)
        // Equality follows the consumed CString; trailing bytes after an embedded NUL
        // do not make a distinct ASCII language. Full CF Unicode equality is not claimed.
        invalid(core() + (13 to byteArrayOf(101, 110, 0, 88, 0)), 13)
    }

    @Test fun emptySupportedLanguageIsAllowedOnceButDuplicateEmptyFails() {
        assertEquals(listOf(""), parse(replace(13, byteArrayOf(0))).supportedLanguages)
        invalid(replace(13, byteArrayOf(0)) + (13 to byteArrayOf(0)), 13)
    }

    @Test fun badLanguageFramingAppendsTwoRejectTlvsAndStops() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(65), byteArrayOf(65, 0, 66))) {
            invalid(replace(13, bytes), 13, listOf(13, 13))
            invalid(listOf(13 to bytes) + core(), 13, listOf(13, 13))
        }
    }

    @Test fun requiredCoreFieldsAreCheckedAfterSuccessfulParsing() {
        val required = (0..7).toList() + listOf(12, 13)
        for (id in required) {
            val error = assertThrows(IdentificationMissingParametersException::class.java) {
                parse(core().filterNot { it.first == id })
            }
            assertEquals(listOf(id), error.parameterIds)
        }
        val all = assertThrows(IdentificationMissingParametersException::class.java) { parse(emptyList()) }
        assertEquals(required, all.parameterIds)
    }

    @Test fun firstParsingFailurePrecedesMissingRequiredChecksInWireOrder() {
        invalid(listOf(7 to byteArrayOf(1), 0 to byteArrayOf(65)), 7)
        invalid(listOf(0 to byteArrayOf(65), 7 to byteArrayOf(1)), 0)
        invalid(core().filterNot { it.first == 0 } + (13 to byteArrayOf()), 13, listOf(13, 13))
    }

    @Test fun unknownIdsFailAtTheFrontMiddleAndEndOfAnOtherwiseValidMessage() {
        for (id in listOf(27, 28, 31, 32, 35, 37, 39, 239, 242, 65535)) {
            for (at in listOf(0, 5, core().size)) {
                invalid(core().toMutableList().apply { add(at, id to byteArrayOf()) }, id)
            }
        }
    }

    @Test fun reserved19RejectsEvenThoughItIsRegistered() {
        invalid(core() + (19 to byteArrayOf()), 19)
        invalid(listOf(19 to byteArrayOf(1)) + core(), 19)
    }

    @Test fun registeredFeatureInputsAreDeferredNotCountedAsValidatedCore() {
        val deferred = setOf(10, 11, 14, 15, 18, 20, 21, 22, 23, 25, 26, 29, 30, 33, 38, 240, 241)
        // Deliberately opaque values: retaining these is a documented parser boundary,
        // not an assertion that Apple or complete feature validators accept them.
        val fields = IapFields(core() + listOf(
            8 to byteArrayOf(2), 9 to IapWire.be16(0),
            16 to IapWire.fields(listOf(
                0 to IapWire.be16(3), 1 to IapWire.cstring("usb-host"),
                2 to byteArrayOf(), 3 to byteArrayOf(1), 4 to byteArrayOf())),
        ) + deferred.map { it to byteArrayOf() })
        assertEquals(deferred, IapIdentifyInfo.deferredParameterIds(fields))
        assertEquals(fields.ordered.map { it.first }, IapIdentifyInfo.parse(fields).rawParameters.map { it.id })
        assertTrue(IapIdentifyInfo.deferredParameterIds(IapFields(core())).isEmpty())
    }

    @Test fun failedCandidateCannotMutateAPreviouslyAcceptedIdentity() {
        val accepted = parse(core())
        invalid(core() + listOf(0 to IapWire.cstring("uncommitted"), 7 to byteArrayOf(1)), 7)
        assertEquals("identity-0", accepted.name)
        assertEquals(listOf(MessageId(0x57, 2)), accepted.accessoryReceives)
    }

    @Test fun initialParserDoesNotRelaxGlobalSingletonFieldsOrTheUpdateContract() {
        val fields = IapFields(listOf(0 to IapWire.cstring("one"), 0 to IapWire.cstring("two")))
        assertThrows(IllegalArgumentException::class.java) { fields[0] }
        val accepted = parse(core() + (34 to IapWire.cstring("old-ppid")))
        val updated = IapIdentificationUpdate.apply(accepted, IapFields(listOf(
            12 to IapWire.cstring("different"), 13 to IapWire.cstring("different"),
            34 to IapWire.cstring("different"), 65535 to byteArrayOf(),
        )))
        assertEquals(accepted, updated)
    }
}
