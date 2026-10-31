package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.sessioncore.HandoffSupervisor.ResourceRef
import io.github.iamr0s.carvex.sessioncore.HandoffSupervisor.State
import io.github.iamr0s.carvex.sessioncore.HandoffSupervisor.FailureReason
import org.junit.Assert.*
import org.junit.Test

class HandoffSupervisorTest {
    private val source=ResourceRef("bootstrap-link",4,7)
    private val target=ResourceRef("runtime-link",9,1)
    private val network=ResourceRef("wifi-lease",73)
    private class Clock(var time:Long=0) { fun now()=time }

    @Test fun sourceMayDetachBeforeTargetAttachAndOnlyTargetConditionsCommit(){
        val clock=Clock()
        val supervisor=HandoffSupervisor(clock::now)
        supervisor.arm(source,target,"peer",network,100)
        val pending=supervisor.sourceDetach(source)
        assertEquals(State.CUTOVER_PENDING,pending.state)
        assertTrue(pending.sourceDetached);assertFalse(pending.committed)
        assertEquals(network,pending.networkLease)
        assertEquals(State.TARGET_ATTACHED,supervisor.targetAttach(target,"peer").state)
        assertFalse(supervisor.targetAuthenticationVerified(target).committed)
        assertFalse(supervisor.targetIdentificationAccepted(target).committed)
        val committed=supervisor.airplayReady(target)
        assertTrue(committed.committed)
        assertEquals(network,committed.networkLease)
        assertEquals(committed,supervisor.arm(source,target,"peer",network,100))
    }

    @Test fun targetMayBecomeReadyBeforeSourceDetachAndEventsNeedNotHaveOneGlobalOrder(){
        val supervisor=HandoffSupervisor{0}
        supervisor.arm(source,target,"peer",network,100)
        assertFalse(supervisor.airplayReady(target).committed)
        assertFalse(supervisor.targetAuthenticationVerified(target).committed)
        assertFalse(supervisor.targetIdentificationAccepted(target).committed)
        val committed=supervisor.targetAttach(target,"peer")
        assertTrue(committed.committed);assertFalse(committed.sourceDetached)
        assertTrue(supervisor.sourceDetach(source).committed)
    }

    @Test fun unknownMigrationAndWrongPeerCannotTurnEofOrAttachIntoSuccess(){
        val unknown=HandoffSupervisor{0}.sourceDetach(source)
        assertEquals(State.HANDOFF_FAILED,unknown.state)
        assertEquals(FailureReason.UNKNOWN_MIGRATION,unknown.failure?.reason)
        val supervisor=HandoffSupervisor{0}
        supervisor.arm(source,target,"expected-peer",network,100)
        val wrong=supervisor.targetAttach(target,"another-peer")
        assertEquals(FailureReason.WRONG_PEER,wrong.failure?.reason)
        assertFalse(supervisor.airplayReady(target).committed)
        assertFalse(supervisor.targetAuthenticationVerified(target).committed)
        assertFalse(supervisor.targetIdentificationAccepted(target).committed)
    }

    @Test fun callbacksMatchTheirOwnOwnerGenerationAndLinkEpoch(){
        val supervisor=HandoffSupervisor{0}
        val armed=supervisor.arm(source,target,"peer",network,100)
        assertEquals(armed,supervisor.sourceDetach(source.copy(generation=3)))
        assertEquals(armed,supervisor.targetAttach(target.copy(linkEpoch=0),"peer"))
        assertEquals(armed,supervisor.targetAuthenticationVerified(target.copy(generation=8)))
        assertEquals(armed,supervisor.targetIdentificationAccepted(target.copy(ownerId="old-link")))
        assertEquals(armed,supervisor.airplayReady(source))
        assertEquals(armed,supervisor.targetFailure(target.copy(linkEpoch=2)))
        assertEquals(armed,supervisor.networkLost(network.copy(generation=72)))
        assertEquals(network,supervisor.sourceDetach(source).networkLease)
        assertTrue(supervisor.snapshot.pending)
    }

    @Test fun oldCallbacksCannotChangeAReplacementMigration(){
        val supervisor=HandoffSupervisor{0}
        supervisor.arm(source,target,"peer",network,100,requiresTargetIap=false)
        supervisor.targetAttach(target,"peer");assertTrue(supervisor.airplayReady(target).committed)
        val newSource=source.copy(generation=5)
        val newTarget=target.copy(generation=10,linkEpoch=2)
        val newNetwork=network.copy(generation=74)
        val armed=supervisor.arm(newSource,newTarget,"new-peer",newNetwork,200)
        assertEquals(armed,supervisor.sourceDetach(source))
        assertEquals(armed,supervisor.targetAuthenticationVerified(target))
        assertEquals(armed,supervisor.networkLost(network))
        assertEquals(armed,supervisor.targetFailure(target))
        assertFalse(supervisor.snapshot.committed)
    }

    @Test fun totalDeadlineIsNotExtendedBySourceDetachOrPartialTargetProgress(){
        val clock=Clock()
        val supervisor=HandoffSupervisor(clock::now)
        supervisor.arm(source,target,"peer",network,100)
        clock.time=90
        supervisor.sourceDetach(source);supervisor.targetAttach(target,"peer")
        supervisor.targetAuthenticationVerified(target);supervisor.targetIdentificationAccepted(target)
        clock.time=100
        val expired=supervisor.airplayReady(target)
        assertEquals(State.HANDOFF_FAILED,expired.state)
        assertEquals(FailureReason.DEADLINE,expired.failure?.reason)
        assertFalse(expired.committed)
        assertEquals(expired,supervisor.arm(source,target,"peer",network,100))
        assertEquals(FailureReason.DEADLINE,HandoffSupervisor{100}.arm(source,target,"peer",network,100).failure?.reason)
    }

    @Test fun matchingNetworkFailureAndTargetFailureKeepTheirFailureCategory(){
        val supervisor=HandoffSupervisor{0}
        supervisor.arm(source,target,"peer",network,100)
        assertEquals(FailureReason.NETWORK_LOST,supervisor.networkLost(network).failure?.reason)
        val retry=HandoffSupervisor{0}
        retry.arm(source,target,"peer",network,100)
        val failure=retry.targetFailure(target,TransportErrorCategory.AUTH_FAILED)
        assertEquals(TransportErrorCategory.AUTH_FAILED,failure.failure?.category)
        assertEquals(failure,retry.targetAttach(target,"peer"))
        assertEquals(failure,retry.airplayReady(target))
    }

    @Test fun explicitlySelectedDirectAirplaySchemaDoesNotRequireAnUnnegotiatedRuntimeIap(){
        val supervisor=HandoffSupervisor{0}
        supervisor.arm(source,target,"peer",network,100,requiresTargetIap=false)
        assertFalse(supervisor.airplayReady(target).committed)
        assertTrue(supervisor.targetAttach(target,"peer").committed)
        assertFalse(supervisor.snapshot.targetAuthenticationVerified)
        assertFalse(supervisor.snapshot.targetIdentificationAccepted)
    }
}
