package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test

class IapIdentificationValidationTest {
    private fun info(sends: List<Int> = emptyList(), receives: List<Int> = emptyList(), type: Int = 17): IdentifyInfo {
        fun ids(values: List<Int>) = values.flatMap { IapWire.be16(it).toList() }.toByteArray()
        val component = listOf(0 to IapWire.be16(0), 1 to IapWire.cstring("synthetic")) +
            if (type == 17) listOf(3 to ByteArray(6)) else listOf(4 to byteArrayOf())
        val fields = (0..5).map { it to IapWire.cstring("test") } + listOf(
            6 to ids(sends), 7 to ids(receives), 12 to IapWire.cstring("en"),
            13 to IapWire.cstring("en"), 8 to byteArrayOf(2), 9 to IapWire.be16(0),
            type to IapWire.fields(component))
        return IapIdentifyInfo.parse(IapFields(fields))
    }
    private fun validate(info: IdentifyInfo, context: IdentificationEndpointContext = IdentificationEndpointContext.BLUETOOTH_RFCOMM) =
        IapIdentificationValidation.validate(info, context)
    private fun rejected(sends: List<Int>, receives: List<Int>): List<RawParameter> =
        assertThrows(IdentificationMessageValidationException::class.java) { validate(info(sends, receives)) }.rejectedParameters
    private fun raw(vararg ids: Int) = ids.flatMap { IapWire.be16(it).toList() }.toByteArray()

    @Test fun endpointContextIsLocalAndRequiresItsOwnComponentEvenWithNoMessageGroups() {
        val bt = info()
        val air = info(type = 24)
        validate(bt)
        validate(air, IdentificationEndpointContext.AIRPLAY_IAP)
        for ((candidate, context, missing) in listOf(
            Triple(bt, IdentificationEndpointContext.AIRPLAY_IAP, 24),
            Triple(air, IdentificationEndpointContext.BLUETOOTH_RFCOMM, 17),
            Triple(bt.copy(components = emptyList()), IdentificationEndpointContext.BLUETOOTH_RFCOMM, 17))) {
            val error = assertThrows(IdentificationMissingParametersException::class.java) { validate(candidate, context) }
            assertEquals(listOf(missing), error.parameterIds)
        }
        // Native required-field collection precedes the feature/message validators.
        val missingBoth = bt.rawParameters.filterNot { it.id == 0 || it.id == 17 }.map { it.id to it.value }
        val both = assertThrows(IdentificationMissingParametersException::class.java) {
            IapIdentifyInfo.parse(IapFields(missingBoth), IdentificationTransportKind.BLUETOOTH)
        }
        assertEquals(listOf(0, 17), both.parameterIds)
        val malformed = assertThrows(IdentificationParameterException::class.java) {
            IapIdentifyInfo.parse(IapFields(missingBoth + (7 to byteArrayOf(1))), IdentificationTransportKind.BLUETOOTH)
        }
        assertEquals(7, malformed.parameterId) // Callback failure still precedes required collection.
    }

    @Test fun allEightCombinationsOfAbsentAndCompleteGroupsAreValid() {
        for (mask in 0..7) {
            val sends = buildList { if (mask and 1 != 0) add(0x5700); if (mask and 2 != 0) add(0x5703); if (mask and 4 != 0) add(0x4301) }
            val receives = buildList { if (mask and 1 != 0) add(0x5701); if (mask and 2 != 0) add(0x5702); if (mask and 4 != 0) add(0x4300) }
            val checked = validate(info(sends, receives))
            assertEquals(mask and 3 != 0, 22 in checked.validatedMessageFeatures)
            assertEquals(mask and 4 != 0, 26 in checked.validatedMessageFeatures)
            assertEquals(mask and 2 != 0, checked.networkHandoff.wifiAnnouncement)
            assertEquals(mask and 4 != 0, checked.networkHandoff.carPlayStartSession)
            assertEquals(mask and 6 != 0, checked.networkHandoff.hasSupportedRoute)
        }
    }

    @Test fun eachHalfGroupRejectsOnlyTheMissingDirectionWithNoIdPayload() {
        for ((sent, received) in listOf(0x5700 to 0x5701, 0x5703 to 0x5702, 0x4301 to 0x4300)) {
            assertEquals(listOf(RawParameter(7, byteArrayOf())), rejected(listOf(sent), emptyList()))
            assertEquals(listOf(RawParameter(6, byteArrayOf())), rejected(emptyList(), listOf(received)))
        }
    }

    @Test fun reversedGroupsAreUnaccountedAndListIdsInTheirActualDirection() {
        for ((sent, received) in listOf(0x5700 to 0x5701, 0x5703 to 0x5702, 0x4301 to 0x4300)) {
            assertEquals(listOf(RawParameter(6, raw(received)), RawParameter(7, raw(sent))),
                rejected(listOf(received), listOf(sent)))
        }
    }

    @Test fun aCorrectPairDoesNotAccountForAnExtraReverseDirectionCopy() {
        assertEquals(listOf(RawParameter(6, raw(0x4300)), RawParameter(7, raw(0x4301))),
            rejected(listOf(0x4301, 0x4300), listOf(0x4300, 0x4301)))
    }

    @Test fun wifiFirstFailureSkipsSecondGroupButStillChecksCarPlayAndUnaccountedIds() {
        val errors = rejected(listOf(0x5700, 0x5703), listOf(0x5702, 0x4300))
        assertEquals(listOf(RawParameter(6, raw(0x5703)), RawParameter(7, raw(0x5702))), errors)
        // Missing 5701 and 4301 add flags only. No reason bytes or missing IDs.
        assertArrayEquals(byteArrayOf(0, 6, 0, 6, 0x57, 3, 0, 6, 0, 7, 0x57, 2),
            IapWire.fields(errors.map { it.id to it.value }))
    }

    @Test fun wifiSecondFailureDoesNotSkipCarPlayValidation() {
        assertEquals(listOf(RawParameter(6, byteArrayOf()), RawParameter(7, byteArrayOf())),
            rejected(listOf(0x5703), listOf(0x4300)))
    }

    @Test fun otherFeaturesIdsAreDeferredNotBlanketRejectedByPartialOwnershipCheck() {
        val checked = validate(info(listOf(0xaa01, 0xae00, 0xabcd, 0x00ff), listOf(0xaa00, 0xae01, 0xfffa)))
        assertTrue(checked.validatedMessageFeatures.isEmpty())
        assertFalse(checked.networkHandoff.hasSupportedRoute)
    }

    @Test fun componentEvidenceIsIndependentOfRoutesAndIsNotAnInventedHardGate() {
        val routeWithoutFlag = validate(info(listOf(0x5703), listOf(0x5702)))
        assertEquals(ComponentCapabilityEvidence.NOT_ADVERTISED, routeWithoutFlag.componentCapabilities.carPlay)
        assertTrue(routeWithoutFlag.networkHandoff.wifiAnnouncement)
        val flagWithoutRoute = validate(info(type = 24), IdentificationEndpointContext.AIRPLAY_IAP)
        assertEquals(ComponentCapabilityEvidence.ADVERTISED, flagWithoutRoute.componentCapabilities.carPlay)
        assertFalse(flagWithoutRoute.networkHandoff.hasSupportedRoute)
    }

    @Test fun repeatedMessageListsAreValidatedOnlyAfterTheirFinalReplacement() {
        val original = info(listOf(0x5703), listOf(0x5702))
        val fields = original.rawParameters.map { it.id to it.value }
        val cleared = IapIdentifyInfo.parse(IapFields(fields + listOf(6 to byteArrayOf(), 7 to byteArrayOf())))
        assertFalse(validate(cleared).networkHandoff.hasSupportedRoute)
        val half = IapIdentifyInfo.parse(IapFields(fields + (6 to byteArrayOf())))
        assertEquals(listOf(RawParameter(6, byteArrayOf())),
            assertThrows(IdentificationMessageValidationException::class.java) { validate(half) }.rejectedParameters)
    }

    @Test fun acceptedUpdateCannotChangeComponentsOrRoutesOrFeatureFlags() {
        val initial = validate(info(listOf(0x5703), listOf(0x5702)))
        val session = AcceptedIdentification(1, 2, initial)
        session.handle(io.github.iamr0s.carvex.carplay.core.IapCommand.Fields(0x1d06,
            IapFields(listOf(0 to IapWire.cstring("new"), 6 to byteArrayOf(),
                17 to byteArrayOf(), 24 to byteArrayOf()))), 1, 2) {}
        assertEquals("new", session.info.name)
        assertSame(initial.components, session.info.components)
        assertSame(initial.componentCapabilities, session.info.componentCapabilities)
        assertSame(initial.networkHandoff, session.info.networkHandoff)
        assertEquals(initial.validatedMessageFeatures, session.info.validatedMessageFeatures)
        assertEquals(initial.networkHandoff, session.capabilities.networkHandoff)
    }

    @Test fun powerFieldsAreRequiredAndBoundedByTheBluetoothProvider() {
        val complete = info()
        val withoutPower = IapIdentifyInfo.parse(IapFields(
            complete.rawParameters.filterNot { it.id == 8 || it.id == 9 }.map { it.id to it.value }))
        val missing = assertThrows(IdentificationMissingParametersException::class.java) {
            validate(withoutPower)
        }
        assertEquals(listOf(8, 9), missing.parameterIds)

        val tooHigh = IapIdentifyInfo.parse(IapFields(
            complete.rawParameters.map {
                if (it.id == 9) it.id to IapWire.be16(101) else it.id to it.value
            }))
        val rejected = assertThrows(IdentificationParameterException::class.java) {
            validate(tooHigh)
        }
        assertEquals(9, rejected.parameterId)
    }

    @Test fun toyotaConfigVectorAcceptsPowerAndAllThreeDeclaredTransports() {
        fun ids(values: List<Int>) = values.flatMap { IapWire.be16(it).toList() }.toByteArray()
        fun component(type: Int, vararg children: Pair<Int, ByteArray>): Pair<Int, ByteArray> =
            type to IapWire.fields(children.toList())
        val fields = listOf(
            0 to IapWire.cstring("TOYOTA"),
            1 to IapWire.cstring("CAR MULTIMEDIA"),
            2 to IapWire.cstring("Shenzhen Hangsheng Electronics Co., Ltd"),
            3 to IapWire.cstring("SN8906120011"),
            4 to IapWire.cstring("V01.00"),
            5 to IapWire.cstring("V01"),
            6 to ids(listOf(0x4301, 0x5703)),
            7 to ids(listOf(0x4300, 0x5702)),
            8 to byteArrayOf(2),
            9 to IapWire.be16(0),
            12 to IapWire.cstring("en"),
            13 to IapWire.cstring("en"),
            component(16,
                0 to IapWire.be16(3), 1 to IapWire.cstring("HSAE-USBHost"),
                2 to byteArrayOf(), 3 to byteArrayOf(1), 4 to byteArrayOf()),
            component(17,
                0 to IapWire.be16(7), 1 to IapWire.cstring("HSAE-Bluetooth"),
                2 to byteArrayOf(), 3 to ByteArray(6)),
            component(24,
                0 to IapWire.be16(8), 1 to IapWire.cstring("HSAE-Wireless"),
                2 to byteArrayOf(), 4 to byteArrayOf()),
            34 to IapWire.cstring("c9ab63cccc1a477a"),
        )
        val parsed = IapIdentifyInfo.parse(IapFields(fields), IdentificationTransportKind.BLUETOOTH)
        val accepted = validate(parsed)
        assertEquals(2, accepted.powerProvidingCapability)
        assertEquals(0, accepted.maximumCurrentDrawnFromDevice)
        assertEquals(setOf(IdentificationTransportKind.USB_HOST,
            IdentificationTransportKind.BLUETOOTH,
            IdentificationTransportKind.WIRELESS_CARPLAY),
            accepted.components.map { it.kind }.toSet())
        assertEquals(setOf(22, 26), accepted.validatedMessageFeatures)
    }
}
