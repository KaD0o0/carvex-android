package io.github.iamr0s.carvex.sessioncore

/** One explicit migration intent (docs/01,04,07). Socket, timer and network owners retain their
 * own resources; a source Link EOF cannot acquire or release the target network lease here. */
class HandoffSupervisor(private val now:()->Long=System::nanoTime) {
    /** ownerId identifies the resource owner, not a display name or authenticated peer identity.
     * Link references include linkEpoch; a network owner may leave its independent epoch at 0. */
    data class ResourceRef(val ownerId:String,val generation:Long,val linkEpoch:Long=0) {
        init { require(ownerId.isNotBlank()&&ownerId.length<=128&&generation>=0&&linkEpoch>=0) }
    }
    enum class State { IDLE, HANDOFF_ARMED, CUTOVER_PENDING, TARGET_ATTACHED, HANDOFF_COMMITTED, HANDOFF_FAILED }
    enum class FailureReason { UNKNOWN_MIGRATION, WRONG_PEER, DEADLINE, TARGET_FAILED, NETWORK_LOST }
    data class Failure(val reason:FailureReason,val category:TransportErrorCategory)
    data class Snapshot(
        val state:State=State.IDLE,
        val source:ResourceRef?=null,
        val target:ResourceRef?=null,
        val networkLease:ResourceRef?=null,
        val sourceDetached:Boolean=false,
        val targetAttached:Boolean=false,
        val targetAuthenticationVerified:Boolean=false,
        val targetIdentificationAccepted:Boolean=false,
        val airplaySessionReady:Boolean=false,
        val failure:Failure?=null,
    ) {
        val committed:Boolean get()=state==State.HANDOFF_COMMITTED
        val pending:Boolean get()=state in setOf(State.HANDOFF_ARMED,State.CUTOVER_PENDING,State.TARGET_ATTACHED)
    }
    private data class Intent(val source:ResourceRef,val target:ResourceRef,val peerCandidate:String,
        val networkLease:ResourceRef,val deadlineNanos:Long,val requiresTargetIap:Boolean)
    private var intent:Intent?=null
    private var current=Snapshot()
    val snapshot:Snapshot
        @Synchronized get()=current

    /** Only the selected migration schema may authorize this call. peerCandidate correlates the
     * candidate, while target authentication remains a separate, mandatory event when required. */
    @Synchronized fun arm(source:ResourceRef,target:ResourceRef,peerCandidate:String,
        networkLease:ResourceRef,deadlineNanos:Long,requiresTargetIap:Boolean=true):Snapshot {
        require(source!=target&&peerCandidate.isNotBlank()&&peerCandidate.length<=256)
        val next=Intent(source,target,peerCandidate,networkLease,deadlineNanos,requiresTargetIap)
        if(intent==next)return checkDeadline()
        check(!current.pending)
        intent=next
        current=Snapshot(State.HANDOFF_ARMED,source,target,networkLease)
        return checkDeadline()
    }

    private fun fail(reason:FailureReason,category:TransportErrorCategory=TransportErrorCategory.HANDOFF_FAILED):Snapshot {
        current=current.copy(state=State.HANDOFF_FAILED,failure=Failure(reason,category))
        return current
    }
    private fun active():Boolean {
        if(intent==null){fail(FailureReason.UNKNOWN_MIGRATION);return false}
        checkDeadline()
        return current.state!=State.HANDOFF_FAILED
    }
    /** Call from the owner's bounded deadline task. No polling thread or global timer is created. */
    @Synchronized fun checkDeadline():Snapshot {
        val context=intent
        if(context!=null&&current.pending&&now()>=context.deadlineNanos)fail(FailureReason.DEADLINE)
        return current
    }
    private fun commitIfReady():Snapshot {
        val context=checkNotNull(intent)
        if(current.targetAttached&&current.airplaySessionReady&&
            (!context.requiresTargetIap||(current.targetAuthenticationVerified&&current.targetIdentificationAccepted)))
            current=current.copy(state=State.HANDOFF_COMMITTED)
        return current
    }

    /** A matching EOF is expected only after arm(). Stale resource callbacks do not alter a newer
     * migration or invalidate its independently owned network lease. */
    @Synchronized fun sourceDetach(source:ResourceRef):Snapshot {
        if(!active()||source!=intent?.source)return current
        current=current.copy(sourceDetached=true,state=when {
            current.committed->State.HANDOFF_COMMITTED
            current.targetAttached->State.TARGET_ATTACHED
            else->State.CUTOVER_PENDING
        })
        return current
    }
    @Synchronized fun targetAttach(target:ResourceRef,peerCandidate:String):Snapshot {
        if(!active()||target!=intent?.target)return current
        if(peerCandidate!=intent?.peerCandidate)return fail(FailureReason.WRONG_PEER)
        if(current.committed)return current
        current=current.copy(targetAttached=true,state=State.TARGET_ATTACHED)
        return commitIfReady()
    }
    @Synchronized fun targetAuthenticationVerified(target:ResourceRef):Snapshot {
        if(!active()||target!=intent?.target)return current
        current=current.copy(targetAuthenticationVerified=true)
        return commitIfReady()
    }
    @Synchronized fun targetIdentificationAccepted(target:ResourceRef):Snapshot {
        if(!active()||target!=intent?.target)return current
        current=current.copy(targetIdentificationAccepted=true)
        return commitIfReady()
    }
    @Synchronized fun airplayReady(target:ResourceRef):Snapshot {
        if(!active()||target!=intent?.target)return current
        current=current.copy(airplaySessionReady=true)
        return commitIfReady()
    }
    @Synchronized fun targetFailure(target:ResourceRef,
        category:TransportErrorCategory=TransportErrorCategory.HANDOFF_FAILED):Snapshot {
        if(!active()||target!=intent?.target)return current
        return fail(FailureReason.TARGET_FAILED,category)
    }
    @Synchronized fun networkLost(networkLease:ResourceRef):Snapshot {
        if(!active()||networkLease!=intent?.networkLease)return current
        return fail(FailureReason.NETWORK_LOST)
    }
}
