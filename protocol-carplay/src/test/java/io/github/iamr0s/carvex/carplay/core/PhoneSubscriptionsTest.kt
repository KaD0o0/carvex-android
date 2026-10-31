package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSubscriptionsTest {
    private fun fields(vararg pairs: Pair<Int, ByteArray>) = IapFields(pairs.toList())

    @Test fun emptySubscriptionAppliesTheDefaultFieldSet(){
        assertTrue(PhoneSubscriptions.startCallStateUpdates(fields()))
        assertEquals(setOf(0, 1, 2, 3, 4), PhoneSubscriptions.callStateFields)
        assertTrue(PhoneSubscriptions.callStateDefaultSet)
        assertTrue(PhoneSubscriptions.callStateActive)
    }

    @Test fun onlyParamFiveAlsoKeepsTheDefaultSet(){
        assertTrue(PhoneSubscriptions.startCallStateUpdates(fields(5 to ByteArray(0))))
        // 固件先把 id 本身加进集合，再追加默认集 0..4，所以 id 5 也在集合里。
        assertEquals(setOf(0, 1, 2, 3, 4, 5), PhoneSubscriptions.callStateFields)
        assertTrue(PhoneSubscriptions.callStateDefaultSet)
        assertTrue(PhoneSubscriptions.sawParam5)
    }

    @Test fun explicitListReplacesTheDefaultSet(){
        assertTrue(PhoneSubscriptions.startCallStateUpdates(fields(6 to ByteArray(0), 7 to ByteArray(0))))
        assertEquals(setOf(6, 7), PhoneSubscriptions.callStateFields)
        assertFalse(PhoneSubscriptions.callStateDefaultSet)
    }

    @Test fun nonEmptyPayloadFailsTheWholeSubscription(){
        assertFalse(PhoneSubscriptions.startCallStateUpdates(fields(1 to byteArrayOf(9))))
        assertFalse(PhoneSubscriptions.callStateActive)
    }

    @Test fun unknownIdsAreIgnoredAndStopClearsState(){
        assertTrue(PhoneSubscriptions.startCallStateUpdates(fields(0x0d to ByteArray(0), 2 to ByteArray(0))))
        assertEquals(setOf(2), PhoneSubscriptions.callStateFields)
        PhoneSubscriptions.stopCallStateUpdates()
        assertFalse(PhoneSubscriptions.callStateActive)
        assertEquals(emptySet<Int>(), PhoneSubscriptions.callStateFields)
    }

    @Test fun communicationSubscriptionUsesTheSameShape(){
        assertTrue(PhoneSubscriptions.startCommunicationUpdates(fields(0 to ByteArray(0), 3 to ByteArray(0))))
        assertEquals(setOf(0, 3), PhoneSubscriptions.communicationFields)
        PhoneSubscriptions.stopCommunicationUpdates()
        assertFalse(PhoneSubscriptions.communicationActive)
    }

    @Test fun callStateUpdateOnlyEmitsSubscribedFieldsAndLegacyValues(){
        assertTrue(PhoneSubscriptions.startCallStateUpdates(fields(0 to ByteArray(0), 1 to ByteArray(0),
            2 to ByteArray(0), 3 to ByteArray(0), 4 to ByteArray(0), 6 to ByteArray(0))))
        val out = PhoneSubscriptions.callStateUpdate(setOf(0, 1, 2, 3, 4, 6),
            PhoneSubscriptions.CallState(remoteId = "+1", displayName = "Bob", status = 1, direction = 2,
                callUuid = "uuid", addressBookId = "ab"))
        assertEquals(listOf(0, 1, 2, 3, 4, 6), out.map { it.first })
        assertEquals(3, out.first { it.first == 2 }.second[0].toInt()) // legacyStatus(1)=3
        assertEquals(1, out.first { it.first == 3 }.second[0].toInt()) // direction 2 -> 1
        // 0x0a 只在会议通话时出现。
        assertFalse(out.any { it.first == 0x0a })
    }

    @Test fun communicationUpdateUsesDefaultValues(){
        assertTrue(PhoneSubscriptions.startCommunicationUpdates(fields(0 to ByteArray(0), 4 to ByteArray(0), 5 to ByteArray(0))))
        val out = PhoneSubscriptions.communicationUpdate(setOf(0, 4, 5), PhoneSubscriptions.Communications())
        assertEquals(listOf(0, 4, 5), out.map { it.first })
        assertEquals(0, out.first { it.first == 0 }.second[0].toInt())
        assertEquals(0, out.first { it.first == 5 }.second[0].toInt())
        assertEquals(0, out.first { it.first == 4 }.second.last().toInt())
    }
}
