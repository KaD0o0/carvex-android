package io.github.iamr0s.carvex.carplay.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalAudioAndDestinationTest {
    private fun group(vararg children: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((id, payload) in children) {
            val total = 4 + payload.size
            out.write((total ushr 8) and 0xff); out.write(total and 0xff)
            out.write((id ushr 8) and 0xff); out.write(id and 0xff)
            out.write(payload)
        }
        return out.toByteArray()
    }

    @Test fun digitalAudioIsAPlainSwitch(){
        DigitalAudioAndDestination.startDigitalAudio()
        assertTrue(DigitalAudioAndDestination.digitalAudioActive)
        DigitalAudioAndDestination.stopDigitalAudio()
        assertFalse(DigitalAudioAndDestination.digitalAudioActive)
    }

    @Test fun destinationStartRequiresVoidUniqueParamZero(){
        DigitalAudioAndDestination.setAvailableFeatures(setOf(0x17))
        assertTrue(DigitalAudioAndDestination.startDestination(IapFields(listOf(0 to ByteArray(0)))))
        assertTrue(DigitalAudioAndDestination.startDestination(IapFields(emptyList())))
        assertFalse(DigitalAudioAndDestination.startDestination(IapFields(listOf(0 to byteArrayOf(1)))))
        DigitalAudioAndDestination.stopDestination()
        assertFalse(DigitalAudioAndDestination.destinationActive)
        // feature 0x17 不存在时按固件拒绝。
        DigitalAudioAndDestination.setAvailableFeatures(emptySet())
        assertFalse(DigitalAudioAndDestination.startDestination(IapFields(emptyList())))
    }

    @Test fun da01AndDestinationUpdateEncodeFirmwareFields(){
        val info = DigitalAudioAndDestination.digitalAudioInfo(48, 100, 1)
        assertEquals(listOf(0, 1, 2), info.map { it.first })
        assertEquals(48, info[0].second[0].toInt())
        DigitalAudioAndDestination.setAvailableFeatures(setOf(0x17))
        DigitalAudioAndDestination.startDestination(IapFields(emptyList()))
        val update = DigitalAudioAndDestination.destinationUpdate(
            displayName = "Home", center = 37.3349 to -122.0090, coordinateThreshold = 10)
        assertEquals(listOf(0, 2, 5), update.map { it.first })
        // Q10.22：37.3349 × 2²² 的 BE32。
        val lat = IapWire.parseFieldList(update.first { it.first == 2 }.second)[0]!!
        val expected = java.nio.ByteBuffer.allocate(4).putInt(Math.round(37.3349 * 4194304.0).toInt()).array()
        org.junit.Assert.assertArrayEquals(expected, lat)
        DigitalAudioAndDestination.stopDestination()
    }

    @Test fun destinationStatusRecordsTextFailureAndGroupKeys(){
        val fields = IapFields(listOf(
            0 to "arrived".toByteArray() + byteArrayOf(0),
            2 to group(3 to ByteArray(0)),
        ))
        assertTrue(DigitalAudioAndDestination.destinationStatusUpdate(fields))
        assertEquals("arrived", DigitalAudioAndDestination.destinationStatus)
        assertFalse(DigitalAudioAndDestination.destinationFailed)
        assertEquals(setOf(3), DigitalAudioAndDestination.destinationReasonKeys)

        assertTrue(DigitalAudioAndDestination.destinationStatusUpdate(IapFields(listOf(1 to ByteArray(0)))))
        assertTrue(DigitalAudioAndDestination.destinationFailed)
    }

    @Test fun destinationStatusRejectsNonVoidChild(){
        val fields = IapFields(listOf(2 to group(2 to byteArrayOf(7))))
        assertFalse(DigitalAudioAndDestination.destinationStatusUpdate(fields))
    }
}
