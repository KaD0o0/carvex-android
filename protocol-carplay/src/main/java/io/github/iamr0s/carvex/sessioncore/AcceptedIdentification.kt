package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapCommand

/**
 * Identity state for one accepted control-link generation/epoch. Construct only
 * after local 1D02 send success. This is not an authentication/trust assertion.
 * The owner must invalidate this object when its control link closes or degrades.
 */
class AcceptedIdentification(val generation: Int, val epoch: Long, initial: IdentifyInfo) {
    private var active = true
    @Volatile var info: IdentifyInfo = initial
        private set

    // Updates cannot widen or shrink the initially accepted message declarations.
    val capabilities = CapabilityRegistry(
        receivedByAccessory = initial.accessoryReceives.toSet(),
        sentByAccessory = initial.accessorySends.toSet(),
        transportComponents = initial.components.map { it.kind.carrier }.toSet(),
        componentCapabilities = initial.componentCapabilities,
        networkHandoff = initial.networkHandoff,
    )

    /** Retain the last diagnostic snapshot, but reject all subsequent dispatches. */
    @Synchronized
    fun invalidate() {
        active = false
    }

    @Synchronized
    fun requireCurrent(currentGeneration: Int, currentEpoch: Long) {
        check(active) { "iAP identification session is no longer active" }
        check(currentGeneration == generation && currentEpoch == epoch) {
            "iAP identification link generation/epoch changed"
        }
    }

    /** Consumes accepted-state Identification messages without emitting any reply. */
    @Synchronized
    fun handle(command: IapCommand, currentGeneration: Int, currentEpoch: Long,
               log: (String) -> Unit): Boolean {
        requireCurrent(currentGeneration,currentEpoch)
        when (command.id) {
            0x1d01 -> log("iAP 1d01 ignored: identification already accepted")
            0x1d05 -> log("iAP 1d05 ignored: identification already accepted")
            0x1d06 -> {
                val fields = command.fields
                if (fields == null) {
                    log("1d06 identification update ignored: no TLV fields")
                } else {
                    val updated = try {
                        IapIdentificationUpdate.apply(info, fields)
                    } catch (error: IdentificationParameterException) {
                        log("1d06 identification update dropped parameter=" + error.parameterId)
                        return true
                    }
                    info = updated
                    log("INFO 1d06 update identityFields=" +
                        fields.ordered.map { it.first }.filter { it in 0..5 }.distinct() +
                        " declarations unchanged")
                }
            }
            else -> return false
        }
        return true
    }
}
