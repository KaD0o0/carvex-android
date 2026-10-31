package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.IapFields

/** Peer declarations from iAP2 4301, not local identity or Bonjour metadata.
 * Missing mutualAuth remains unknown; it never establishes trust or selects a route.
 * 24A446 accessoryd 0x10016d3ac parses strings 5/6 and Bool 8 in wire order.
 * A malformed known value rejects that message; repeated valid scalar values replace.
 */
data class StartSessionPeerInfo(
    val sourceVersion: String? = null,
    val sdkVersion: String? = null,
    val supportsMutualAuthentication: Boolean? = null,
) {
    // Values are peer-controlled. Do not expose arbitrary strings via ordinary logs.
    override fun toString() = "StartSessionPeerInfo(sourceVersionPresent=" + (sourceVersion != null) +
        ", sdkVersionPresent=" + (sdkVersion != null) + ", mutualAuth=" + supportsMutualAuthentication + ")"

    companion object {
        internal fun parse(fields: IapFields): StartSessionPeerInfo {
            val top = StartSessionFields(fields)
            var sourceVersion: String? = null
            var sdkVersion: String? = null
            var mutualAuth: Boolean? = null
            fun text(id: Int, raw: ByteArray): String {
                // iAP2MsgGetDataAsString@0x100067ee4 requires the final TLV byte
                // to be NUL. Do not broaden all existing IapWire.text consumers.
                if (raw.isEmpty() || raw.last() != 0.toByte())
                    top.fail(id, StartSessionProblem.INVALID_TEXT, raw.size)
                return top.decodeText(id, raw)
            }
            for ((id, raw) in fields.ordered) {
                when (id) {
                    5 -> sourceVersion = text(id, raw)
                    6 -> sdkVersion = text(id, raw)
                    8 -> {
                        if (raw.size != 1) top.fail(id, StartSessionProblem.INVALID_WIDTH, raw.size)
                        val value = raw[0].toInt() and 255
                        if (value !in 0..1) top.fail(id, StartSessionProblem.INVALID_BOOLEAN, raw.size)
                        mutualAuth = value == 1
                    }
                }
            }
            return StartSessionPeerInfo(sourceVersion, sdkVersion, mutualAuth)
        }
    }
}
