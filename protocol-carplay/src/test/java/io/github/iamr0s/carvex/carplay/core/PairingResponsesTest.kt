package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class PairingResponsesTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun publishedErrorsDoNotBecomeSuccessOrRecordDeletion() {
        for ((body, state) in listOf("060102070102" to 2, "060104070102" to 4)) {
            val error = assertThrows(SecurityFailure::class.java) { PairingResponses.validate(Tlv8.decode(hex(body)), state, false, 200) }
            assertEquals(SecurityReason.PAIR_PEER_AUTH_REJECTED, error.diagnostic.localReason)
            assertEquals(2, error.diagnostic.pairingError)
            assertEquals(200, error.diagnostic.wireStatus)
            assertNull(error.diagnostic.sourceStatus)
            assertNull(error.retryAfterSeconds)
        }
        val backoff = assertThrows(SecurityFailure::class.java) { PairingResponses.validate(Tlv8.decode(hex("06010207010308013c")), 2, true, 200) }
        assertEquals(SecurityReason.PAIR_BACKOFF, backoff.diagnostic.localReason)
        assertEquals(60L, backoff.retryAfterSeconds)
    }
    @Test fun allDelayWidthsBoundsAndInvalidErrorShapes() {
        for (width in listOf(1, 2, 4, 8)) assertEquals(60L, PairingResponses.retryDelay(ByteArray(width).also { it[0] = 60 }))
        for (body in listOf(byteArrayOf(), ByteArray(3), ByteArray(8) { 0xff.toByte() }, ByteArray(1), byteArrayOf(0,0,0,0x80.toByte())))
            assertNull(PairingResponses.retryDelay(body))
        for (error in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(2,2))) {
            assertEquals(SecurityReason.PAIR_STATE_INVALID, assertThrows(SecurityFailure::class.java) {
                PairingResponses.validate(mapOf(6 to byteArrayOf(2), 7 to error), 2, true, 200)
            }.diagnostic.localReason)
        }
        assertEquals(SecurityReason.PAIR_PEER_ERROR, assertThrows(SecurityFailure::class.java) {
            PairingResponses.validate(mapOf(6 to byteArrayOf(2), 7 to byteArrayOf(3), 8 to byteArrayOf(0)), 2, true, 200)
        }.diagnostic.localReason)
    }
    @Test fun absentOrMismatchedCseqIsNotATransactionMismatch() {
        for (headers in listOf(emptyMap(),mapOf("CSeq" to "99"))) {
            val input=ByteArrayOutputStream().also{RtspWire.write(it,"RTSP/1.0 200 OK",headers,Tlv8.encode(Tlv8.state(2)))}
            val client=RtspClient(ByteArrayInputStream(input.toByteArray()),ByteArrayOutputStream())
            val error=runCatching{HomeKit(client,PairIdentity("synthetic",ByteArray(32))).setup(setupCode=byteArrayOf(1))}.exceptionOrNull()
            assertFalse("CSeq 回显不能决定配对结果",
                error is SecurityFailure&&error.diagnostic.localReason==SecurityReason.TRANSACTION_MISMATCH)
        }
    }
    @Test fun homeKitStopsAfterBackoffWithoutSendingM3() {
        val response = ByteArrayOutputStream().also { RtspWire.write(it,"RTSP/1.0 200 OK",mapOf("CSeq" to "1"),hex("06010207010308013c")) }
        val out = ByteArrayOutputStream()
        val h = HomeKit(RtspClient(ByteArrayInputStream(response.toByteArray()),out),PairIdentity("synthetic",ByteArray(32)))
        assertEquals(60L, assertThrows(SecurityFailure::class.java) { h.setup(setupCode=byteArrayOf(1)) }.retryAfterSeconds)
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("/pair-setup",RtspWire.read(input).first.split(" ")[1])
        assertEquals(0,input.available())
    }
}
