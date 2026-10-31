package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class U2iwHidReportCodecTest {
    @Test fun detectionMatchesRecoveredU2iwReport() {
        assertArrayEquals(byteArrayOf(2, 0, -1, 0x55, 2, 0, 0xee.toByte(), 0x10, 0, 0),
            U2iwHidReportCodec.encodeInput(IapWire.detection))
    }

    @Test fun strictCountThresholdIncludesFlagAndExcludesId() {
        val counts = intArrayOf(5, 9, 13, 17, 25, 49, 95, 193, 257, 385, 513, 767)
        for ((index, count) in counts.withIndex()) {
            val payload = ByteArray(count - 1) { 0x7a }
            val report = U2iwHidReportCodec.encodeInput(payload)
            assertEquals(index + 1, report[0].toInt() and 0xff)
            assertEquals(count + 1, report.size)
            assertEquals(0, report[1].toInt())
            assertArrayEquals(payload, report.copyOfRange(2, report.size))
            if (index < counts.lastIndex) {
                assertEquals(index + 2, U2iwHidReportCodec.encodeInput(ByteArray(count))[0].toInt())
            }
        }
        assertFailure(UsbIap2FramingError.SINGLE_REPORT_CAPACITY) {
            U2iwHidReportCodec.encodeInput(ByteArray(767))
        }
    }

    @Test fun outputIdsUseTheirOwnCountTableAndKeepFlagExplicit() {
        val report = byteArrayOf(14, 0) + IapWire.detection + byteArrayOf(0, 0)
        val decoded = U2iwHidReportCodec.decodeOutput(report)
        assertEquals(14, decoded.id)
        assertEquals(0, decoded.flag)
        assertArrayEquals(IapWire.detection + byteArrayOf(0, 0), decoded.zeroFlagPayload())
        assertFailure(UsbIap2FramingError.REPORT_ID) { U2iwHidReportCodec.decodeOutput(byteArrayOf(12, 0)) }
        assertFailure(UsbIap2FramingError.REPORT_LENGTH) { U2iwHidReportCodec.decodeOutput(ByteArray(7).apply { this[0] = 13 }) }
        assertFailure(UsbIap2FramingError.REPORT_LENGTH) { U2iwHidReportCodec.decodeOutput(report.copyOf(8)) }
        val flagged = U2iwHidReportCodec.decodeOutput(report.copyOf().apply { this[1] = 2 })
        assertFailure(UsbIap2FramingError.UNVERIFIED_FRAGMENT_FLAG) { flagged.zeroFlagPayload() }
    }

    @Test fun completeOutputDropsOnlyPaddingAfterTheKnownPacketEnd(){
        val detection=byteArrayOf(14,0)+IapWire.detection+ByteArray(2)
        assertArrayEquals(IapWire.detection,U2iwHidReportCodec.decodeCompleteOutput(detection))
        val link=IapWire.packet(IapWire.Frame(0x40,7,2,10,byteArrayOf(0,1,0,2)))
        val report=byteArrayOf(16,0)+link+ByteArray(16-link.size)
        assertEquals(18,report.size)
        assertArrayEquals(link,U2iwHidReportCodec.decodeCompleteOutput(report))
        assertFailure(UsbIap2FramingError.NONZERO_PADDING){
            U2iwHidReportCodec.decodeCompleteOutput(detection.copyOf().apply{this[lastIndex]=1})
        }
    }

    @Test fun completeOutputCannotTreatPaddingAsAnUnverifiedFragment(){
        val report=byteArrayOf(14,0,0xff.toByte(),0x5a,0,16,0x40,1,0,0)
        assertFailure(UsbIap2FramingError.FRAME_LENGTH){U2iwHidReportCodec.decodeCompleteOutput(report)}
        val invalidDetection=byteArrayOf(14,0)+IapWire.detection.copyOf().apply{this[5]=0}+ByteArray(2)
        assertFailure(UsbIap2FramingError.FRAME_MARKER){U2iwHidReportCodec.decodeCompleteOutput(invalidDetection)}
        assertFailure(UsbIap2FramingError.UNVERIFIED_FRAGMENT_FLAG){
            U2iwHidReportCodec.decodeCompleteOutput((byteArrayOf(14,0)+IapWire.detection+ByteArray(2)).apply{this[1]=1})
        }
        val shortHeader=byteArrayOf(13,0,0xff.toByte(),0x5a,0,10)
        assertFailure(UsbIap2FramingError.FRAME_LENGTH){U2iwHidReportCodec.decodeCompleteOutput(shortHeader)}
    }

    @Test fun dewrappedPayloadHandlesHalfPacketsConcatenationAndPadding() {
        val frame = IapWire.packet(IapWire.Frame(0x40, 7, 2, 10, byteArrayOf(0, 1, 0, 2)))
        val decoder = Iap2UsbPacketDecoder(766)
        assertTrue(decoder.offer(IapWire.detection.copyOfRange(0, 3)).isEmpty())
        val detected = decoder.offer(IapWire.detection.copyOfRange(3, 6) + ByteArray(3) + frame.copyOfRange(0, 11))
        assertEquals(1, detected.size)
        assertArrayEquals(IapWire.detection, detected.single())
        val packets = decoder.offer(frame.copyOfRange(11, frame.size) + ByteArray(7) + frame + ByteArray(2))
        assertEquals(2, packets.size)
        assertArrayEquals(frame, packets[0])
        assertArrayEquals(frame, packets[1])
    }

    @Test fun oversizedPacketFailsBeforeAllocationAndDetachClearsFragments() {
        val decoder = Iap2UsbPacketDecoder(766)
        assertFailure(UsbIap2FramingError.FRAME_LENGTH) {
            decoder.offer(byteArrayOf(-1, 0x5a, 3, 0))
        }
        decoder.offer(byteArrayOf(-1, 0x5a, 0))
        decoder.reset()
        assertArrayEquals(IapWire.detection, decoder.offer(IapWire.detection).single())
    }

    private fun assertFailure(reason: UsbIap2FramingError, operation: () -> Unit) {
        try { operation(); fail("Expected $reason") }
        catch (failure: UsbIap2FramingException) { assertEquals(reason, failure.reason) }
    }
}
