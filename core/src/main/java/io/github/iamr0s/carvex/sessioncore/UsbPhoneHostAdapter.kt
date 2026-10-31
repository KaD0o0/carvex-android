package io.github.iamr0s.carvex.sessioncore

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** CarPlay product role and USB controller role are independent. */
enum class UsbProductRole { PHONE_HOST }
enum class UsbControllerRole { DEVICE, HOST, SWITCHING }
enum class UsbIap2Carrier { HID_REPORTS, KERNEL_IAP2_STREAM, BULK }

/** A snapshot of ACTUAL platform readiness; a queued role request never sets interfaceReady. */
data class UsbPhoneHostEndpoint(
    val peer: PeerEndpoint,
    val generation: Long,
    val usbRole: UsbControllerRole,
    val carrier: UsbIap2Carrier,
    val interfaceReady: Boolean,
    val maxReceivePacketBytes: Int,
    val maxTransmitPacketBytes: Int,
) {
    val productRole: UsbProductRole get() = UsbProductRole.PHONE_HOST
    init {
        require(generation >= 0)
        require(maxReceivePacketBytes in 9..65535)
        require(maxTransmitPacketBytes in 9..65535)
    }
}

/**
 * Implemented by a system gadget/OTG service or a target-specific native driver, not UsbManager.
 * The service owns descriptors, control requests, actual role/interface events and NCM networking.
 * It increments generation on detach, endpoint rebuilding role changes and mode exit; it must
 * close old endpoints to wake blocked reads/writes. It must not emit readiness merely on request.
 *
 * open() returns unwrapped iAP2 bytes. /dev/ipod_iap2 already strips ID/flag; a raw HID backend must
 * strip exactly once, remove padding using iAP2 lengths and enforce its verified fragmentation
 * limits. maxReceivePacketBytes must match the advertised SYN receive limit. NCM carrier/address
 * readiness, authentication and AirPlay/media readiness are separate, later session stages.
 */
interface UsbPhoneHostPlatform {
    suspend fun discover(): List<UsbPhoneHostEndpoint>
    fun currentEndpoint(id: String): UsbPhoneHostEndpoint?
    suspend fun open(endpoint: UsbPhoneHostEndpoint, deadlineNanos: Long): ByteStream
}

/** Explicit default for ordinary Android builds, which cannot supply Phone gadget/HNP APIs. */
object UnsupportedUsbPhoneHostPlatform : UsbPhoneHostPlatform {
    private fun unsupported(): Nothing {
        throw TransportException(TransportErrorCategory.PLATFORM_UNSUPPORTED, "USB Phone HOST needs a system gadget/OTG service; Android USB Host API is insufficient")
    }
    override suspend fun discover(): List<UsbPhoneHostEndpoint> = unsupported()
    override fun currentEndpoint(id: String): UsbPhoneHostEndpoint? = null
    override suspend fun open(endpoint: UsbPhoneHostEndpoint, deadlineNanos: Long): ByteStream = unsupported()
}

/** Injectable USB entry point. No hardware support is claimed until a platform supplies endpoints. */
class UsbPhoneHostTransportAdapter(
    private val platform: UsbPhoneHostPlatform = UnsupportedUsbPhoneHostPlatform,
) : TransportAdapter {
    override val mode: ConnectionMode = ConnectionMode.CARPLAY_USB
    private val discovered = ConcurrentHashMap<String, UsbPhoneHostEndpoint>()

    override suspend fun discover(): List<PeerEndpoint> {
        val endpoints = platform.discover()
        discovered.clear()
        endpoints.forEach { discovered[it.peer.id] = it }
        return endpoints.map { it.peer }
    }

    /** Link negotiation must use these platform limits instead of a product-wide constant. */
    fun endpointInfo(endpoint: PeerEndpoint): UsbPhoneHostEndpoint? = discovered[endpoint.id]

    override suspend fun open(endpoint: PeerEndpoint, deadlineNanos: Long): ByteStream {
        val selected = discovered[endpoint.id]
            ?: throw TransportException(TransportErrorCategory.SERVICE_NOT_FOUND, TransportErrorCategory.SERVICE_NOT_FOUND.name)
        validate(selected)
        checkDeadline(deadlineNanos)
        val delegate = platform.open(selected, deadlineNanos)
        val guarded = GenerationStream(delegate, selected)
        try {
            validate(selected)
            checkDeadline(deadlineNanos)
        } catch (failure: Throwable) {
            runCatching { guarded.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return guarded
    }

    private fun validate(selected: UsbPhoneHostEndpoint) {
        val actual = platform.currentEndpoint(selected.peer.id)
        if (actual == null || actual.generation != selected.generation) {
            throw TransportException(TransportErrorCategory.STREAM_EOF, TransportErrorCategory.STREAM_EOF.name)
        }
        if (!actual.interfaceReady || actual.usbRole == UsbControllerRole.SWITCHING) {
            throw TransportException(TransportErrorCategory.TRANSPORT_UNAVAILABLE, TransportErrorCategory.TRANSPORT_UNAVAILABLE.name)
        }
    }

    private fun checkDeadline(deadlineNanos: Long) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw TransportException(TransportErrorCategory.CONNECT_TIMEOUT, TransportErrorCategory.CONNECT_TIMEOUT.name)
        }
    }

    private inner class GenerationStream(
        private val delegate: ByteStream,
        private val endpoint: UsbPhoneHostEndpoint,
    ) : ByteStream {
        private val closed = AtomicBoolean()
        private fun guard() {
            if (closed.get()) throw TransportException(TransportErrorCategory.STREAM_EOF, TransportErrorCategory.STREAM_EOF.name)
            try { validate(endpoint) } catch (failure: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        override suspend fun read(maxBytes: Int, deadlineNanos: Long): ByteArray {
            require(maxBytes > 0)
            guard()
            checkDeadline(deadlineNanos)
            val bytes = delegate.read(maxBytes, deadlineNanos)
            guard() // A completion from the old generation must never reach the new session.
            checkDeadline(deadlineNanos)
            return bytes
        }
        override suspend fun write(bytes: ByteArray, deadlineNanos: Long) {
            guard()
            checkDeadline(deadlineNanos)
            delegate.write(bytes, deadlineNanos)
            guard()
            checkDeadline(deadlineNanos)
        }
        override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
    }
}
