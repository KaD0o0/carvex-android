package io.github.iamr0s.carvex.carplay.core

/** The U2IW profile is a vendor HID transport, not a keyboard/media-key report. */
object U2iwHidReportCodec {
    private val inputCounts = intArrayOf(5, 9, 13, 17, 25, 49, 95, 193, 257, 385, 513, 767)
    private val outputCounts = intArrayOf(5, 9, 13, 17, 25, 49, 95, 193, 255)
    val MAX_SINGLE_INPUT_PAYLOAD = inputCounts.max()-1
    val MAX_SINGLE_OUTPUT_PAYLOAD = outputCounts.max()-1

    /** Phone gadget -> USB host. Count includes flag, excludes report ID. No TX splitting is inferred. */
    fun encodeInput(payload: ByteArray): ByteArray {
        val index = inputCounts.indexOfFirst { payload.size < it }
        if (index < 0) throw UsbIap2FramingException(UsbIap2FramingError.SINGLE_REPORT_CAPACITY)
        return ByteArray(inputCounts[index] + 1).apply {
            this[0] = (index + 1).toByte()
            payload.copyInto(this, 2)
        }
    }

    /** USB host -> Phone gadget SET_REPORT data, including ID and flag. */
    fun decodeOutput(report: ByteArray): OutputReport {
        if (report.size < 2) throw UsbIap2FramingException(UsbIap2FramingError.REPORT_LENGTH)
        val id = report[0].toInt() and 0xff
        val count = outputCounts.getOrNull(id - 13)
            ?: throw UsbIap2FramingException(UsbIap2FramingError.REPORT_ID)
        // HID_REPORT_TABLE_V1 is a fixed-length raw report. Short USB reads must be
        // accumulated by the adapter; another framing variant needs its own schema.
        if (report.size != count + 1) throw UsbIap2FramingException(UsbIap2FramingError.REPORT_LENGTH)
        return OutputReport(id, report[1].toInt() and 0xff, report.copyOfRange(2, report.size))
    }

    /** Verified flag-zero, one-complete-packet variant; never infer a continuation from padding. */
    fun decodeCompleteOutput(report:ByteArray,maxPacketBytes:Int=MAX_SINGLE_OUTPUT_PAYLOAD):ByteArray =
        decodeOutput(report).completePacket(maxPacketBytes)

    data class OutputReport(val id: Int, val flag: Int, val payloadWithPadding: ByteArray) {
        /** Raw bytes including padding. This is NOT a verified fragment length for ByteStream. */
        fun zeroFlagPayload(): ByteArray {
            if (flag != 0) throw UsbIap2FramingException(UsbIap2FramingError.UNVERIFIED_FRAGMENT_FLAG)
            return payloadWithPadding.copyOf()
        }
        fun completePacket(maxPacketBytes:Int):ByteArray {
            require(maxPacketBytes in 9..65535)
            val bytes=zeroFlagPayload()
            if(bytes.size<2||bytes[0]!=0xff.toByte())fail(UsbIap2FramingError.FRAME_MARKER)
            val length=when(bytes[1].toInt() and 255){
                0x55->{
                    if(bytes.size<IapWire.detection.size)fail(UsbIap2FramingError.FRAME_LENGTH)
                    if(!bytes.copyOfRange(0,IapWire.detection.size).contentEquals(IapWire.detection))fail(UsbIap2FramingError.FRAME_MARKER)
                    IapWire.detection.size
                }
                0x5a->{
                    if(bytes.size<4)fail(UsbIap2FramingError.FRAME_LENGTH)
                    IapWire.u16(bytes,2).also{if(it !in 9..maxPacketBytes||it==10||it>bytes.size)fail(UsbIap2FramingError.FRAME_LENGTH)}
                }
                else->fail(UsbIap2FramingError.FRAME_MARKER)
            }
            if((length until bytes.size).any{bytes[it]!=0.toByte()})fail(UsbIap2FramingError.NONZERO_PADDING)
            return bytes.copyOf(length)
        }
        private fun fail(reason:UsbIap2FramingError):Nothing=throw UsbIap2FramingException(reason)
    }
}

enum class UsbIap2FramingError {
    REPORT_ID, REPORT_LENGTH, SINGLE_REPORT_CAPACITY, UNVERIFIED_FRAGMENT_FLAG,
    FRAME_MARKER, FRAME_LENGTH, NONZERO_PADDING,
}

class UsbIap2FramingException(val reason: UsbIap2FramingError) : IllegalArgumentException(reason.name)

/**
 * Bounded USB byte-stream deframer. Removes only zero padding BETWEEN packets;
 * zeros inside a Link packet are data. Detection is its own fixed six-byte packet.
 * Link checksum/session validation remains with IapLink. Feed dewrapped bytes, never report ID/flag.
 *
 * Calls may split or concatenate an already validated byte stream. A raw HID adapter must first
 * establish effective payload length and a verified fragment/end contract. Passing unknown HID
 * fragments (including their zero padding) here can fabricate frame bytes and is forbidden.
 */
class Iap2UsbPacketDecoder(val maxPacketBytes: Int) {
    init { require(maxPacketBytes in 9..65535) }
    private val pending = ByteArray(maxPacketBytes)
    private var count = 0
    private var expected = 0

    fun reset() { count = 0; expected = 0 }

    fun offer(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        try {
            for (byte in bytes) {
                if (count == 0 && byte == 0.toByte()) continue
                pending[count++] = byte
                if (count == 1 && byte != 0xff.toByte()) fail(UsbIap2FramingError.FRAME_MARKER)
                if (count == 2) {
                    expected = when (byte.toInt() and 0xff) {
                        0x55 -> IapWire.detection.size
                        0x5a -> 0
                        else -> fail(UsbIap2FramingError.FRAME_MARKER)
                    }
                }
                if (count >= 2 && pending[1] == 0x55.toByte() && byte != IapWire.detection[count - 1]) {
                    fail(UsbIap2FramingError.FRAME_MARKER)
                }
                if (count == 4 && pending[1] == 0x5a.toByte()) {
                    expected = ((pending[2].toInt() and 0xff) shl 8) or (pending[3].toInt() and 0xff)
                    if (expected !in 9..maxPacketBytes || expected == 10) fail(UsbIap2FramingError.FRAME_LENGTH)
                }
                if (expected != 0 && count == expected) {
                    packets += pending.copyOf(count)
                    reset()
                }
            }
        } catch (failure: UsbIap2FramingException) {
            reset()
            throw failure
        }
        return packets
    }

    private fun fail(reason: UsbIap2FramingError): Nothing = throw UsbIap2FramingException(reason)
}
