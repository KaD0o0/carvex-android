package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapControlSessionKind

/** A message observed on the iAP2 control plane; receipt alone is not verification. */
data class PeerMessage(val id: MessageId)

enum class NegotiationAction {
    NONE,
    START_AUTH,
    START_IDENTIFICATION,
    TERMINATE,
}

/** Independent AA and Identification transactions for one negotiated link generation. */
class PeerNegotiationController(
    private val preferred: FallbackAttempt = FallbackAttempt.AUTH_FIRST,
    private val controlSessionId: Int? = null,
    private val controlSessionVersion: Int? = null,
    private val controlSessionKind: IapControlSessionKind = IapControlSessionKind.OTHER,
) {
    private var auth = AuthState.IDLE
    private var identification = IdentificationState.IDLE
    private var first = FirstPeerEvent.NONE
    private var startOrder = FallbackAttempt.NONE
    private var receives: Set<MessageId> = emptySet()
    private var sends: Set<MessageId> = emptySet()

    fun observe(message: PeerMessage): NegotiationAction {
        if (first == FirstPeerEvent.NONE) first = when (message.id.value) {
            0xaa01 -> FirstPeerEvent.AA_01
            0xaa03 -> FirstPeerEvent.AA_03
            0x1d01 -> FirstPeerEvent.ID_01
            0x1d03 -> FirstPeerEvent.ID_03
            else -> FirstPeerEvent.NONE
        }
        return when (message.id.value) {
            0xaa01, 0xaa03 -> {
                if (auth == AuthState.IDLE) auth = AuthState.REQUESTED
                NegotiationAction.NONE
            }
            0xaa04 -> {
                auth = AuthState.FAILED
                NegotiationAction.TERMINATE
            }
            // Receipt cannot start or restart the phone's Identification transaction.
            // The consumer admits 1D/01 only after its local 1D/00 send completed,
            // or after a local rejection was successfully sent on this same link.
            0x1d01 -> NegotiationAction.NONE
            0x1d03 -> {
                // Local fail-fast policy for an unexpected peer rejection. This is
                // separate from our own 1D/03, which permits a corrected 1D/01.
                identification = IdentificationState.REJECTED
                sends = emptySet()
                receives = emptySet()
                NegotiationAction.TERMINATE
            }
            // AA/05 is sent by the Phone after *local* signature verification.
            // A peer AA/05 or 1D/02 must not manufacture a successful local transaction.
            else -> NegotiationAction.NONE
        }
    }

    /** Select the schema-derived start order immediately, at most once per link. */
    fun preferredStart(): NegotiationAction {
        if (first != FirstPeerEvent.NONE || auth != AuthState.IDLE ||
            identification != IdentificationState.IDLE || startOrder != FallbackAttempt.NONE) return NegotiationAction.NONE
        startOrder = preferred
        return start(preferred)
    }

    private fun start(order: FallbackAttempt): NegotiationAction = when (order) {
        FallbackAttempt.AUTH_FIRST -> {
            auth = AuthState.REQUESTED
            NegotiationAction.START_AUTH
        }
        FallbackAttempt.IDENT_FIRST -> {
            identification = IdentificationState.STARTING
            NegotiationAction.START_IDENTIFICATION
        }
        FallbackAttempt.NONE -> NegotiationAction.NONE
    }

    /** Call only after the verifier checked this session's challenge signature. */
    fun authenticationVerified(experimentalIntegrityOnly:Boolean=false): NegotiationAction {
        check(auth == AuthState.REQUESTED)
        auth = if(experimentalIntegrityOnly)AuthState.EXPERIMENTAL_INTEGRITY else AuthState.VERIFIED
        return if (identification == IdentificationState.IDLE) start(FallbackAttempt.IDENT_FIRST)
            else NegotiationAction.NONE
    }

    /** Commit 1D/00 only after the current-generation local send completed successfully. */
    fun identificationStarted() {
        check(identification == IdentificationState.STARTING)
        identification = IdentificationState.STARTED
    }

    /** 24A446 admits initial/corrected 1D/01 in its states 1/3, not after acceptance. */
    val canAcceptIdentification: Boolean
        get() = identification == IdentificationState.STARTED || identification == IdentificationState.RETRY_PENDING

    /** Validated candidate, before sending 1D/02. Send failure must not become a retry. */
    fun identificationAccepting() {
        check(canAcceptIdentification)
        identification = IdentificationState.ACCEPTING
    }

    /** Called only after 1D/02's local send succeeded; this is not peer business readiness. */
    fun identificationAccepted(accessorySends: Set<MessageId>, accessoryReceives: Set<MessageId>): NegotiationAction {
        check(identification == IdentificationState.ACCEPTING)
        identification = IdentificationState.ACCEPTED
        sends = accessorySends.toSet()
        receives = accessoryReceives.toSet()
        return if (auth == AuthState.IDLE) start(FallbackAttempt.AUTH_FIRST) else NegotiationAction.NONE
    }

    /**
     * Call only after the local 1D/03 send succeeded. A corrected 1D/01 may then
     * reuse this link without restarting AA. Deadlines/retry budgets belong to
     * the caller; this does not invent an Apple retry timer or retry count.
     */
    fun identificationRejected() {
        check(canAcceptIdentification)
        identification = IdentificationState.RETRY_PENDING
        sends = emptySet()
        receives = emptySet()
    }

    /** 1D/05 cancels only the waiting/retry states, never an accepted identity. */
    fun cancelIdentification(): Boolean {
        if (!canAcceptIdentification) return false
        identification = IdentificationState.CANCELLED
        sends = emptySet()
        receives = emptySet()
        return true
    }

    val authenticationComplete: Boolean get() = auth in setOf(AuthState.VERIFIED,AuthState.EXPERIMENTAL_INTEGRITY)
    val ready: Boolean get() = authenticationComplete && identification == IdentificationState.ACCEPTED

    fun context(transport: LinkTransport, direction: ConnectionDirection): PeerNegotiationContext =
        PeerNegotiationContext(
            transport = transport,
            direction = direction,
            bootstrapRole = when (transport) {
                LinkTransport.BT_RFCOMM -> BootstrapRole.BLUETOOTH
                LinkTransport.TUNNEL -> BootstrapRole.TUNNEL
                LinkTransport.USB -> BootstrapRole.USB
                LinkTransport.WIFI_TCP -> BootstrapRole.TUNNEL
            },
            authState = auth,
            identificationState = identification,
            firstPeerEvent = first,
            accessoryReceives = receives,
            accessorySends = sends,
            fallbackAttempt = startOrder,
            controlSessionId = controlSessionId,
            controlSessionVersion = controlSessionVersion,
            controlSessionKind = controlSessionKind,
        )
}
