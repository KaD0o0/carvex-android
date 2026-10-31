package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerNegotiationControllerTest {
    @Test fun experimentalIntegrityDoesNotBecomeProductionVerified(){
        val controller=PeerNegotiationController()
        controller.preferredStart()
        controller.authenticationVerified(experimentalIntegrityOnly=true)
        controller.identificationStarted()
        controller.identificationAccepting()
        controller.identificationAccepted(emptySet(),emptySet())
        assertTrue(controller.ready)
        assertEquals(AuthState.EXPERIMENTAL_INTEGRITY,controller.context(LinkTransport.BT_RFCOMM,ConnectionDirection.OUTBOUND).authState)
        assertNotEquals(AuthState.VERIFIED,controller.context(LinkTransport.BT_RFCOMM,ConnectionDirection.OUTBOUND).authState)
    }
    @Test fun unsolicitedIdentityCannotStartIdentificationBeforeAuthentication() {
        val controller = PeerNegotiationController(FallbackAttempt.AUTH_FIRST)
        assertEquals(NegotiationAction.START_AUTH, controller.preferredStart())
        assertEquals(NegotiationAction.NONE, controller.observe(PeerMessage(MessageId(0x1d, 0x01))))
        val context = controller.context(LinkTransport.BT_RFCOMM, ConnectionDirection.OUTBOUND)
        assertEquals(IdentificationState.IDLE, context.identificationState)
        assertFalse(controller.canAcceptIdentification)
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepting() }
    }

    @Test fun receiptOfSuccessDoesNotVerifyTheAccessory() {
        val controller = PeerNegotiationController()
        controller.preferredStart()
        controller.observe(PeerMessage(MessageId(0xaa, 0x05)))
        controller.observe(PeerMessage(MessageId(0x1d, 0x02)))
        val context = controller.context(LinkTransport.BT_RFCOMM, ConnectionDirection.INBOUND)
        assertEquals(AuthState.REQUESTED, context.authState)
        assertEquals(IdentificationState.IDLE, context.identificationState)
        assertEquals(false, controller.ready)
    }

    @Test fun preferredStartIsSelectedImmediatelyAndNotRepeated() {
        val controller = PeerNegotiationController()
        assertEquals(NegotiationAction.START_AUTH, controller.preferredStart())
        assertEquals(NegotiationAction.NONE, controller.preferredStart())
        val identificationFirst = PeerNegotiationController(FallbackAttempt.IDENT_FIRST)
        assertEquals(NegotiationAction.START_IDENTIFICATION, identificationFirst.preferredStart())
        assertEquals(IdentificationState.STARTING, identificationFirst.snapshot().identificationState)
    }

    @Test fun anAlreadyObservedPeerEventDoesNotStartACompetingTransaction() {
        val peerFirst = PeerNegotiationController()
        peerFirst.observe(PeerMessage(MessageId(0x1d, 0x01)))
        assertEquals(NegotiationAction.NONE, peerFirst.preferredStart())
        assertEquals(FirstPeerEvent.ID_01, peerFirst.context(LinkTransport.USB, ConnectionDirection.INBOUND).firstPeerEvent)
    }

    @Test fun eitherTransactionCanFinishFirstWithoutDuplicatingTheOther() {
        val controller = PeerNegotiationController(FallbackAttempt.IDENT_FIRST)
        assertEquals(NegotiationAction.START_IDENTIFICATION, controller.preferredStart())
        controller.identificationStarted()
        controller.observe(PeerMessage(MessageId(0x1d, 0x01)))
        val messages = setOf(MessageId(0x43, 0x00))
        controller.identificationAccepting()
        assertEquals(NegotiationAction.START_AUTH, controller.identificationAccepted(emptySet(), messages))
        assertEquals(false, controller.ready)
        assertEquals(NegotiationAction.NONE, controller.authenticationVerified())
        assertEquals(true, controller.ready)
        assertEquals(messages, controller.context(LinkTransport.BT_RFCOMM, ConnectionDirection.OUTBOUND).accessoryReceives)
    }

    @Test fun authenticationFirstStartsIdentificationOnlyAfterLocalVerification() {
        val controller = PeerNegotiationController()
        controller.preferredStart()
        assertEquals(NegotiationAction.START_IDENTIFICATION, controller.authenticationVerified())
        assertEquals(false, controller.ready)
        assertEquals(AuthState.VERIFIED,
            controller.context(LinkTransport.BT_RFCOMM, ConnectionDirection.OUTBOUND).authState)
        controller.identificationStarted()
        controller.observe(PeerMessage(MessageId(0x1d, 0x01)))
        controller.identificationAccepting()
        assertEquals(NegotiationAction.NONE, controller.identificationAccepted(emptySet(), emptySet()))
        assertEquals(true, controller.ready)
    }

    @Test fun failedAuthenticationMakesTheTransactionTerminal() {
        val controller = PeerNegotiationController()
        controller.preferredStart()
        assertEquals(NegotiationAction.TERMINATE, controller.observe(PeerMessage(MessageId(0xaa, 0x04))))
        assertEquals(AuthState.FAILED, controller.snapshot().authState)
        assertEquals(false, controller.ready)
    }

    @Test fun startActionDoesNotAdmitIdentityUntilTheLocalSendCompletes() {
        val controller = PeerNegotiationController(FallbackAttempt.IDENT_FIRST)
        assertEquals(NegotiationAction.START_IDENTIFICATION, controller.preferredStart())
        assertEquals(IdentificationState.STARTING, controller.snapshot().identificationState)
        controller.observe(PeerMessage(MessageId(0x1d, 0x01)))
        assertFalse(controller.canAcceptIdentification)
        assertFalse(controller.cancelIdentification())
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepting() }
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepted(emptySet(), emptySet()) }
        // No successful-send notification means STARTING remains uncommitted.
        assertEquals(IdentificationState.STARTING, controller.snapshot().identificationState)
        controller.identificationStarted()
        assertTrue(controller.canAcceptIdentification)
        assertEquals(IdentificationState.STARTED, controller.snapshot().identificationState)
        assertThrows(IllegalStateException::class.java) { controller.identificationStarted() }
    }

    @Test fun rejectionAllowsCorrectedIdentityOnTheSameLinkWithoutRestartingAuthentication() {
        val controller = waitingForIdentification(authenticated = true)
        controller.identificationRejected()
        assertEquals(IdentificationState.RETRY_PENDING, controller.snapshot().identificationState)
        assertTrue(controller.canAcceptIdentification)
        assertTrue(controller.authenticationComplete)
        assertFalse(controller.ready)
        assertTrue(controller.snapshot().accessorySends.isEmpty())
        assertTrue(controller.snapshot().accessoryReceives.isEmpty())
        assertEquals(NegotiationAction.NONE, controller.observe(PeerMessage(MessageId(0x1d, 0x01))))
        controller.identificationAccepting()
        val sends = setOf(MessageId(0x43, 0x01))
        val receives = setOf(MessageId(0x43, 0x00))
        assertEquals(NegotiationAction.NONE, controller.identificationAccepted(sends, receives))
        assertTrue(controller.ready)
        assertEquals(sends, controller.snapshot().accessorySends)
        assertEquals(receives, controller.snapshot().accessoryReceives)
    }

    @Test fun repeatedLocalRejectionsKeepTheSameWaitingTransaction() {
        val controller = waitingForIdentification()
        repeat(3) {
            controller.identificationRejected()
            assertEquals(IdentificationState.RETRY_PENDING, controller.snapshot().identificationState)
            assertEquals(AuthState.IDLE, controller.snapshot().authState)
            assertTrue(controller.canAcceptIdentification)
            assertFalse(controller.ready)
        }
        // No inferred Apple retry count here: the production caller owns its bounded deadline.
        controller.identificationAccepting()
        assertEquals(NegotiationAction.START_AUTH, controller.identificationAccepted(emptySet(), emptySet()))
        assertFalse(controller.ready)
    }

    @Test fun acceptanceSendMustCompleteBeforeCapabilitiesOrReadinessArePublished() {
        val controller = waitingForIdentification(authenticated = true)
        controller.identificationAccepting()
        assertEquals(IdentificationState.ACCEPTING, controller.snapshot().identificationState)
        assertFalse(controller.canAcceptIdentification)
        assertFalse(controller.ready)
        assertTrue(controller.snapshot().accessorySends.isEmpty())
        assertTrue(controller.snapshot().accessoryReceives.isEmpty())
        assertFalse(controller.cancelIdentification())
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepting() }
        controller.observe(PeerMessage(MessageId(0x1d, 0x01)))
        // If the send fails, callers must not call identificationAccepted or reject again.
        assertEquals(IdentificationState.ACCEPTING, controller.snapshot().identificationState)
        assertEquals(NegotiationAction.NONE, controller.identificationAccepted(emptySet(), emptySet()))
        assertTrue(controller.ready)
    }

    @Test fun acceptedIdentityCannotBeReplacedOrCancelledByLateInitialMessages() {
        val controller = waitingForIdentification(authenticated = true)
        controller.identificationAccepting()
        val sends = setOf(MessageId(0x57, 0x03))
        val receives = setOf(MessageId(0x57, 0x02))
        controller.identificationAccepted(sends, receives)
        val before = controller.snapshot()
        assertEquals(NegotiationAction.NONE, controller.observe(PeerMessage(MessageId(0x1d, 0x01))))
        assertFalse(controller.cancelIdentification())
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepting() }
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepted(emptySet(), emptySet()) }
        assertEquals(before.copy(firstPeerEvent = FirstPeerEvent.ID_01), controller.snapshot())
        assertTrue(controller.ready)
    }

    @Test fun waitingCancellationIsTerminalWithoutARejectionOrNewAuthTransaction() {
        val controller = waitingForIdentification()
        assertTrue(controller.cancelIdentification())
        assertEquals(IdentificationState.CANCELLED, controller.snapshot().identificationState)
        assertEquals(AuthState.IDLE, controller.snapshot().authState)
        assertFalse(controller.canAcceptIdentification)
        assertFalse(controller.ready)
        assertFalse(controller.cancelIdentification())
        controller.observe(PeerMessage(MessageId(0x1d, 0x01)))
        assertEquals(IdentificationState.CANCELLED, controller.snapshot().identificationState)
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepting() }
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
        assertEquals(NegotiationAction.NONE, controller.preferredStart())
    }

    @Test fun retryCancellationRetainsVerifiedAuthenticationButNeverBecomesReady() {
        val controller = waitingForIdentification(authenticated = true)
        controller.identificationRejected()
        assertTrue(controller.cancelIdentification())
        assertEquals(IdentificationState.CANCELLED, controller.snapshot().identificationState)
        assertEquals(AuthState.VERIFIED, controller.snapshot().authState)
        assertTrue(controller.authenticationComplete)
        assertFalse(controller.ready)
        assertTrue(controller.snapshot().accessorySends.isEmpty())
        assertTrue(controller.snapshot().accessoryReceives.isEmpty())
    }

    @Test fun authenticationCompletingAfterCancellationDoesNotRestartIdentification() {
        val controller = waitingForIdentification()
        controller.observe(PeerMessage(MessageId(0xaa, 0x01)))
        assertTrue(controller.cancelIdentification())
        assertEquals(NegotiationAction.NONE, controller.authenticationVerified())
        assertEquals(IdentificationState.CANCELLED, controller.snapshot().identificationState)
        assertFalse(controller.ready)
    }

    @Test fun cancellationInIdleIsIgnored() {
        val controller = PeerNegotiationController()
        assertFalse(controller.cancelIdentification())
        assertEquals(IdentificationState.IDLE, controller.snapshot().identificationState)
        assertThrows(IllegalStateException::class.java) { controller.identificationStarted() }
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
        assertThrows(IllegalStateException::class.java) { controller.identificationAccepted(emptySet(), emptySet()) }
    }

    @Test fun peerRejectionRemainsADistinctTerminalLocalPolicy() {
        val controller = waitingForIdentification(authenticated = true)
        controller.identificationAccepting()
        controller.identificationAccepted(setOf(MessageId(0x43, 0x01)), setOf(MessageId(0x43, 0x00)))
        assertEquals(NegotiationAction.TERMINATE, controller.observe(PeerMessage(MessageId(0x1d, 0x03))))
        assertEquals(IdentificationState.REJECTED, controller.snapshot().identificationState)
        assertFalse(controller.canAcceptIdentification)
        assertFalse(controller.ready)
        assertFalse(controller.cancelIdentification())
        assertTrue(controller.snapshot().accessorySends.isEmpty())
        assertTrue(controller.snapshot().accessoryReceives.isEmpty())
        assertThrows(IllegalStateException::class.java) { controller.identificationRejected() }
    }

    private fun waitingForIdentification(authenticated: Boolean = false): PeerNegotiationController {
        val controller = PeerNegotiationController(if (authenticated) FallbackAttempt.AUTH_FIRST else FallbackAttempt.IDENT_FIRST)
        controller.preferredStart()
        if (authenticated) controller.authenticationVerified()
        controller.identificationStarted()
        return controller
    }

    private fun PeerNegotiationController.snapshot() = context(LinkTransport.BT_RFCOMM, ConnectionDirection.OUTBOUND)
}
