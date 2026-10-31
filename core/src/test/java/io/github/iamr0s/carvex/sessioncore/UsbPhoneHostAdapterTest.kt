package io.github.iamr0s.carvex.sessioncore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsbPhoneHostAdapterTest {
    @Test fun ordinaryAndroidBuildReportsUnsupportedRatherThanPretendingUsbHostIsPhone() = runBlocking {
        val adapter = UsbPhoneHostTransportAdapter()
        try { adapter.discover(); fail("Expected platform support error") }
        catch (failure: TransportException) { assertEquals(TransportErrorCategory.PLATFORM_UNSUPPORTED, failure.category) }
    }

    @Test fun roleRequestCannotOpenUntilAnActualInterfaceIsReady() = runBlocking {
        val platform = FakePlatform().apply { endpoint = endpoint.copy(usbRole = UsbControllerRole.SWITCHING) }
        val adapter = UsbPhoneHostTransportAdapter(platform)
        val selected = adapter.discover().single()
        try { adapter.open(selected, deadline()); fail("Expected readiness error") }
        catch (failure: TransportException) { assertEquals(TransportErrorCategory.TRANSPORT_UNAVAILABLE, failure.category) }
        assertEquals(0, platform.openCount)
    }

    @Test fun generationChangedDuringOpenClosesTheOldStream() = runBlocking {
        val platform = FakePlatform()
        val adapter = UsbPhoneHostTransportAdapter(platform)
        val selected = adapter.discover().single()
        platform.onOpen = { platform.endpoint = platform.endpoint.copy(generation = 2) }
        try { adapter.open(selected, deadline()); fail("Expected stale generation error") }
        catch (failure: TransportException) { assertEquals(TransportErrorCategory.STREAM_EOF, failure.category) }
        assertEquals(1, platform.stream.closeCount)
    }

    @Test fun completionAfterDetachIsDiscardedAndCloseIsIdempotent() = runBlocking {
        val platform = FakePlatform()
        val adapter = UsbPhoneHostTransportAdapter(platform)
        val selected = adapter.discover().single()
        val stream = adapter.open(selected, deadline())
        platform.stream.onRead = { platform.endpoint = platform.endpoint.copy(generation = 2) }
        try { stream.read(12, deadline()); fail("Expected stale completion error") }
        catch (failure: TransportException) { assertEquals(TransportErrorCategory.STREAM_EOF, failure.category) }
        stream.close()
        assertEquals(1, platform.stream.closeCount)
    }

    private fun deadline() = System.nanoTime() + 1_000_000_000

    private class FakePlatform : UsbPhoneHostPlatform {
        var endpoint = UsbPhoneHostEndpoint(PeerEndpoint("usb-test", "test"), 1, UsbControllerRole.DEVICE,
            UsbIap2Carrier.HID_REPORTS, true, 254, 766)
        val stream = FakeStream()
        var onOpen: () -> Unit = {}
        var openCount = 0
        override suspend fun discover() = listOf(endpoint)
        override fun currentEndpoint(id: String) = endpoint.takeIf { it.peer.id == id }
        override suspend fun open(endpoint: UsbPhoneHostEndpoint, deadlineNanos: Long): ByteStream {
            openCount++
            onOpen()
            return stream
        }
    }

    private class FakeStream : ByteStream {
        var closeCount = 0
        var onRead: () -> Unit = {}
        override suspend fun read(maxBytes: Int, deadlineNanos: Long): ByteArray { onRead(); return byteArrayOf(1) }
        override suspend fun write(bytes: ByteArray, deadlineNanos: Long) {}
        override fun close() { closeCount++ }
    }
}
