package io.github.iamr0s.carvex.carplay.core

/** Error2 never authorizes deleting a binding; Error3 is not an immediate retry. */
object PairingResponses {
    fun validate(fields: Map<Int, ByteArray>, expectedState: Int, setup: Boolean, wireStatus: Int) {
        val state = fields[6]
        if (state == null || state.size != 1 || (state[0].toInt() and 255) != expectedState)
            fail(SecurityReason.PAIR_STATE_INVALID, expectedState, wireStatus)
        val error = fields[7] ?: return
        if (error.size != 1 || error[0] == 0.toByte()) fail(SecurityReason.PAIR_STATE_INVALID, expectedState, wireStatus)
        val raw = error[0].toInt() and 255
        val retry = fields[8]?.let(::retryDelay)
        val reason = when {
            raw == 2 -> SecurityReason.PAIR_PEER_AUTH_REJECTED
            setup && raw == 3 && retry != null -> SecurityReason.PAIR_BACKOFF
            else -> SecurityReason.PAIR_PEER_ERROR
        }
        throw SecurityFailure(SecurityDiagnostic("AirPlay-pair", "M$expectedState", reason, wireStatus, raw),
            retryAfterSeconds = retry.takeIf { reason == SecurityReason.PAIR_BACKOFF })
    }
    private fun fail(reason: SecurityReason, state: Int, status: Int): Nothing =
        throw SecurityFailure(SecurityDiagnostic("AirPlay-pair", "M$state", reason, status))
    internal fun retryDelay(bytes: ByteArray): Long? {
        if (bytes.size !in listOf(1, 2, 4, 8)) return null
        var value = 0UL
        bytes.forEachIndexed { index, b -> value = value or ((b.toInt() and 255).toULong() shl (8 * index)) }
        return value.takeIf { it in 1UL..Int.MAX_VALUE.toULong() }?.toLong()
    }
}
