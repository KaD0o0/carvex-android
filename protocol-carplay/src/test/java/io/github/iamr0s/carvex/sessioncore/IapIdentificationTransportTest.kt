package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test

class IapIdentificationTransportTest {
    private fun core() = (0..5).map { it to IapWire.cstring("synthetic-$it") } + listOf(
        6 to byteArrayOf(), 7 to byteArrayOf(),
        12 to IapWire.cstring("en"), 13 to IapWire.cstring("en"))
    private fun children(id: Int = 0) = listOf(0 to IapWire.be16(id), 1 to IapWire.cstring(""))
    private fun bluetooth(id: Int = 0) = children(id) + (3 to byteArrayOf(0, 0, 0, 0, 0, 0))
    private fun component(type: Int, children: List<Pair<Int, ByteArray>>) = type to IapWire.fields(children)
    private fun parse(vararg components: Pair<Int, ByteArray>) = IapIdentifyInfo.parse(IapFields(core() + components))
    private fun invalid(type: Int, children: List<Pair<Int, ByteArray>>) {
        val error = assertThrows(IdentificationParameterException::class.java) { parse(component(type, children)) }
        assertEquals(listOf(type), error.rejectedParameterIds)
    }

    @Test fun requiredChildrenMustOccurExactlyOnce() {
        for (type in listOf(17, 24)) {
            val valid = if (type == 17) bluetooth() else children()
            val required = if (type == 17) listOf(0, 1, 3) else listOf(0, 1)
            for (id in required) {
                invalid(type, valid.filterNot { it.first == id })
                invalid(type, valid + valid.first { it.first == id })
            }
        }
    }

    @Test fun activeIsOptionalButMustBeVoidAndAtMostOnce() {
        for (type in listOf(17, 24)) {
            val valid = if (type == 17) bluetooth() else children()
            parse(component(type, valid))
            parse(component(type, valid + (2 to byteArrayOf())))
            invalid(type, valid + (2 to byteArrayOf(1)))
            invalid(type, valid + listOf(2 to byteArrayOf(), 2 to byteArrayOf()))
        }
    }

    @Test fun knownChildWidthsAndStringTerminationAreChecked() {
        for (type in listOf(17, 24)) {
            val valid = if (type == 17) bluetooth() else children()
            for ((id, bytes) in listOf(0 to byteArrayOf(), 0 to byteArrayOf(0),
                0 to byteArrayOf(0, 0, 0), 1 to byteArrayOf(), 1 to byteArrayOf(65), 1 to byteArrayOf(65, 0, 66))) {
                invalid(type, valid.map { if (it.first == id) id to bytes else it })
            }
        }
    }

    @Test fun bluetoothAddressUsesDocumentedStrictSixByteSafetyBoundary() {
        for (length in listOf(5, 7, 262)) invalid(17, children() + (3 to ByteArray(length)))
        parse(component(17, children() + (3 to ByteArray(6))))
        parse(component(17, children() + (3 to ByteArray(6) { 0xff.toByte() })))
    }

    @Test fun wirelessFlagsAreVoidAndOptionalStringIsTerminated() {
        invalid(24, children() + (4 to byteArrayOf(1)))
        invalid(24, children() + (5 to byteArrayOf(1)))
        invalid(24, children() + (6 to byteArrayOf(65)))
    }

    @Test fun componentIdsAreUniqueAcrossBothParsedTypes() {
        for (second in listOf(component(17, bluetooth()), component(24, children()))) {
            val error = assertThrows(IdentificationParameterException::class.java) {
                parse(component(17, bluetooth()), second)
            }
            assertEquals(listOf(second.first), error.rejectedParameterIds)
        }
        parse(component(17, bluetooth(0)), component(17, bluetooth(1)), component(24, children(2)))
    }

    @Test fun malformedNestedFramingRejectsTheOuterParameter() {
        for (type in listOf(17, 24)) for (bytes in listOf(byteArrayOf(0), byteArrayOf(0, 3, 0, 0), byteArrayOf(0, 8, 0, 0))) {
            val error = assertThrows(IdentificationParameterException::class.java) { parse(type to bytes) }
            assertEquals(listOf(type), error.rejectedParameterIds)
        }
    }

    @Test fun parsedValuesAreIndependentOfInputAndActiveDoesNotFollowContext() {
        val address = byteArrayOf(0, 1, 2, 3, 4, 5)
        val raw = IapWire.fields(children(0) + (3 to address))
        val info = parse(17 to raw)
        raw.fill(0); address.fill(0)
        val component = info.components.single()
        assertEquals(IdentificationTransportKind.BLUETOOTH, component.kind)
        assertEquals(0, component.id); assertEquals("", component.name)
        assertFalse(component.active)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), component.bluetoothAddress)
        assertTrue(parse(component(24, children() + (2 to byteArrayOf()))).components.single().active)
    }

    @Test fun unknownChildrenAreOpaqueAndBluetoothDoesNotInterpretWirelessFlags() {
        val bt = parse(component(17, bluetooth() + listOf(4 to byteArrayOf(99),
            5 to byteArrayOf(88), 6 to byteArrayOf(77), 65535 to byteArrayOf(66))))
        assertFalse(bt.components.single().carPlayFlag)
        assertFalse(bt.componentCapabilities.wirelessCarPlayAdvertised)
        val wireless = parse(component(24, children() + listOf(3 to byteArrayOf(1),
            3 to byteArrayOf(2), 65535 to byteArrayOf(3))))
        assertNull(wireless.components.single().bluetoothAddress)
    }

    @Test fun wirelessRepeatedFlagsAndIdentifierUseTheirOwnCountRules() {
        val info = parse(component(24, children() + listOf(
            4 to byteArrayOf(), 4 to byteArrayOf(), 5 to byteArrayOf(), 5 to byteArrayOf(),
            6 to IapWire.cstring("first"), 6 to IapWire.cstring("last"))))
        assertTrue(info.components.single().carPlayFlag)
        assertTrue(info.components.single().flag5)
        assertEquals("last", info.components.single().identifier6)
        assertEquals(ComponentCapabilityEvidence.ADVERTISED, info.componentCapabilities.carPlay)
        assertTrue(info.componentCapabilities.wirelessCarPlayAdvertised)
        assertTrue(info.componentCapabilities.gatedFlag5)
        assertEquals("last", info.componentCapabilities.gatedIdentifier6)
    }

    @Test fun flag5AloneDoesNotPropagateAndLaterGatedNullReplacesIdentifier() {
        val alone = parse(component(24, children() + listOf(5 to byteArrayOf(), 6 to IapWire.cstring("ignored"))))
        assertTrue(alone.components.single().flag5)
        assertFalse(alone.componentCapabilities.gatedFlag5)
        assertNull(alone.componentCapabilities.gatedIdentifier6)
        val first = component(24, children(0) + listOf(4 to byteArrayOf(), 5 to byteArrayOf(), 6 to IapWire.cstring("old")))
        val ungated = component(24, children(1) + (5 to byteArrayOf()))
        assertEquals("old", parse(first, ungated).componentCapabilities.gatedIdentifier6)
        val last = component(24, children(2) + listOf(4 to byteArrayOf(), 5 to byteArrayOf()))
        assertNull(parse(first, ungated, last).componentCapabilities.gatedIdentifier6)
        assertTrue(parse(first, ungated, last).componentCapabilities.gatedFlag5)
    }

    @Test fun unparsedTransportTypesRemainExplicitlyUnresolved() {
        assertEquals(ComponentCapabilityEvidence.NOT_ADVERTISED,
            parse(component(17, bluetooth())).componentCapabilities.carPlay)
        for (type in listOf(14, 15, 26)) {
            val info = parse(component(17, bluetooth()), type to byteArrayOf())
            assertEquals(ComponentCapabilityEvidence.UNRESOLVED, info.componentCapabilities.carPlay)
            assertEquals(setOf(type), info.componentCapabilities.deferredTransportParameterIds)
        }
        val info = parse(component(24, children() + (4 to byteArrayOf())))
        assertEquals(ComponentCapabilityEvidence.ADVERTISED, info.componentCapabilities.carPlay)
    }

    @Test fun usbHostComponentUsesIdNameActiveNumberAndCarPlayChildren() {
        val component = component(16, children(3) + listOf(
            2 to byteArrayOf(), 3 to byteArrayOf(1), 4 to byteArrayOf()))
        val info = parse(component)
        val parsed = info.components.single()
        assertEquals(IdentificationTransportKind.USB_HOST, parsed.kind)
        assertEquals(3, parsed.id)
        assertEquals("", parsed.name)
        assertTrue(parsed.active)
        assertEquals(1, parsed.usbHostNumber)
        assertTrue(parsed.carPlayFlag)
    }

    @Test fun failedFlaggedCandidateCannotMutateAcceptedComponentsOrCapabilities() {
        val accepted = parse(component(17, bluetooth()))
        val session = AcceptedIdentification(1, 1, accepted)
        assertThrows(IdentificationParameterException::class.java) {
            parse(component(17, bluetooth()), component(24, children() + (4 to byteArrayOf())))
        }
        assertSame(accepted, session.info)
        assertFalse(session.capabilities.componentCapabilities.wirelessCarPlayAdvertised)
        assertEquals(setOf(LinkTransport.BT_RFCOMM), session.capabilities.transportComponents)
    }
}
