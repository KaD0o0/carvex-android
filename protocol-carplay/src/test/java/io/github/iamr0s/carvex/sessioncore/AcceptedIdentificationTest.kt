package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapCommand
import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AcceptedIdentificationTest {
    private fun initial() = IdentifyInfo(
        name = "old", manufacturer = "maker",
        accessorySends = listOf(MessageId(0x57, 3)),
        accessoryReceives = listOf(MessageId(0x57, 2)),
        rawParameters = listOf(RawParameter(0, IapWire.cstring("old"))),
    )
    private fun command(id: Int, vararg fields: Pair<Int, ByteArray>) =
        IapCommand.Fields(id, IapFields(fields.toList()))

    @Test fun acceptedUpdateMergesIdentityButCannotChangeCapabilities() {
        val session = AcceptedIdentification(2, 100L, initial())
        val capabilities = session.capabilities
        val raw = session.info.rawParameters
        assertTrue(session.handle(command(0x1d06,
            0 to IapWire.cstring("private identity"), 6 to byteArrayOf(),
            7 to byteArrayOf(0x43, 0)), 2, 100L) {})
        assertEquals("private identity", session.info.name)
        assertEquals("maker", session.info.manufacturer)
        assertSame(capabilities, session.capabilities)
        assertEquals(setOf(MessageId(0x57, 3)), session.capabilities.sentByAccessory)
        assertEquals(setOf(MessageId(0x57, 2)), session.capabilities.receivedByAccessory)
        assertSame(raw, session.info.rawParameters)
    }

    @Test fun malformedDeltaPublishesNothingAndLaterValidUpdateStillWorks() {
        val session = AcceptedIdentification(2, 100L, initial())
        val previous = session.info
        assertTrue(session.handle(command(0x1d06,
            0 to IapWire.cstring("discard me"), 2 to byteArrayOf(65)), 2, 100L) {})
        assertSame(previous, session.info)
        assertTrue(session.handle(command(0x1d06, 0 to IapWire.cstring("accepted")), 2, 100L) {})
        assertEquals("accepted", session.info.name)
    }

    @Test fun lateInitialAndCancelAreConsumedWithoutReplacingAcceptedIdentity() {
        val session = AcceptedIdentification(2, 100L, initial())
        val previous = session.info
        for (id in listOf(0x1d01, 0x1d05)) {
            assertTrue(session.handle(command(id, 0 to IapWire.cstring("replacement")), 2, 100L) {})
            assertSame(previous, session.info)
        }
    }

    @Test fun unrelatedMessagesRemainAvailableToTheirRuntimeConsumer() {
        val session = AcceptedIdentification(2, 100L, initial())
        assertFalse(session.handle(command(0x5703), 2, 100L) {})
        assertFalse(session.handle(command(0x1d03), 2, 100L) {})
    }

    @Test fun oldGenerationOrEpochIsRejectedBeforeStateOrLogsChange() {
        val session = AcceptedIdentification(2, 100L, initial())
        val previous = session.info
        val logs = mutableListOf<String>()
        for ((generation, epoch) in listOf(3 to 100L, 2 to 101L)) {
            assertThrows(IllegalStateException::class.java) {
                session.handle(command(0x1d06, 0 to IapWire.cstring("stale")), generation, epoch, logs::add)
            }
            assertSame(previous, session.info)
            assertTrue(logs.isEmpty())
        }
    }

    @Test fun nullFieldPayloadDoesNotPublishOrAttemptToParseAnUpdate() {
        val session = AcceptedIdentification(2, 100L, initial())
        val previous = session.info
        val command = IapCommand.Control(0x1d06, 0, byteArrayOf(),
            IapWire.Frame(0, 0, 0, 0, byteArrayOf()))
        assertTrue(session.handle(command, 2, 100L) {})
        assertSame(previous, session.info)
    }

    @Test fun updateLogsOnlyMetadataAndDoesNotExposeIdentityOrMalformedContents() {
        val session = AcceptedIdentification(2, 100L, initial())
        val logs = mutableListOf<String>()
        session.handle(command(0x1d06, 0 to IapWire.cstring("secret-name")), 2, 100L, logs::add)
        session.handle(command(0x1d06, 3 to "secret-serial".toByteArray()), 2, 100L, logs::add)
        assertTrue(logs.any { it.startsWith("INFO 1d06 update") })
        assertTrue(logs.any { it.contains("dropped parameter=3") })
        assertFalse(logs.any { it.contains("secret-name") || it.contains("secret-serial") })
    }

    @Test fun invalidatedSessionRejectsSameEpochCallbacksAndRetainsLastSnapshot() {
        val session = AcceptedIdentification(2, 100L, initial())
        session.handle(command(0x1d06, 0 to IapWire.cstring("latest")), 2, 100L) {}
        val previous = session.info
        session.invalidate()
        session.invalidate()
        for (id in listOf(0x1d01, 0x1d05, 0x1d06, 0x5703)) {
            assertThrows(IllegalStateException::class.java) {
                session.handle(command(id, 0 to IapWire.cstring("closed")), 2, 100L) {
                    fail("invalidated callbacks must not log or publish")
                }
            }
            assertSame(previous, session.info)
        }
    }

    @Test(timeout = 5000) fun invalidateAndUpdateAreSerialized() {
        val session = AcceptedIdentification(2, 100L, initial())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val update = workers.submit<Boolean> {
                session.handle(command(0x1d06, 0 to IapWire.cstring("in flight")), 2, 100L) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val invalidation = workers.submit {
                invalidating.countDown()
                session.invalidate()
            }
            assertTrue(invalidating.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { invalidation.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(update.get(2, TimeUnit.SECONDS))
            invalidation.get(2, TimeUnit.SECONDS)
            assertEquals("in flight", session.info.name)
            assertThrows(IllegalStateException::class.java) {
                session.handle(command(0x1d06, 0 to IapWire.cstring("after close")), 2, 100L) {}
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
