package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire

/** Bounded 24A446 component callbacks (16/17/24), independent of endpoint context. */
internal object IapIdentificationTransport {
    fun parse(parameterId: Int, bytes: ByteArray): IdentificationTransportComponent {
        try {
            val kind = IdentificationTransportKind.entries.single { it.parameterId == parameterId }
            val children = IapWire.parseFieldList(bytes).ordered
            var id = 0
            var name: String? = null
            var active = false
            var address: List<Int>? = null
            var usbHostNumber: Int? = null
            var carPlay = false
            var flag5 = false
            var identifier6: String? = null
            val counts = IntArray(7)
            fun text(value: ByteArray): String {
                require(value.isNotEmpty() && value.last() == 0.toByte()) { "CString requires final NUL" }
                // Same JVM/CF conversion boundary as the core parser; no ASCII restriction.
                return IapWire.text(value)
            }
            for ((child, value) in children) {
                when (child) {
                    0 -> { require(value.size == 2); id = IapWire.u16(value, 0) }
                    1 -> name = text(value)
                    2 -> { require(value.isEmpty()); active = true }
                    3 -> when (kind) {
                        IdentificationTransportKind.BLUETOOTH -> {
                            // Intentional safety tightening: Apple tests (length & 255) == 6.
                            require(value.size == 6)
                            address = value.map { it.toInt() and 255 }
                        }
                        IdentificationTransportKind.USB_HOST -> {
                            require(value.size == 1)
                            usbHostNumber = value[0].toInt() and 255
                        }
                        IdentificationTransportKind.WIRELESS_CARPLAY -> Unit
                    }
                    4 -> when (kind) {
                        IdentificationTransportKind.WIRELESS_CARPLAY -> {
                            require(value.isEmpty()); carPlay = true
                        }
                        IdentificationTransportKind.USB_HOST -> {
                            require(value.isEmpty()); carPlay = true
                        }
                        IdentificationTransportKind.BLUETOOTH -> Unit
                    }
                    5 -> if (kind == IdentificationTransportKind.WIRELESS_CARPLAY) {
                        require(value.isEmpty()); flag5 = true
                    }
                    6 -> if (kind == IdentificationTransportKind.WIRELESS_CARPLAY) identifier6 = text(value)
                    // Unknown children remain in the outer raw value, with no invented meaning.
                }
                if (child in counts.indices) counts[child]++
            }
            require(counts[0] == 1 && counts[1] == 1 && counts[2] <= 1)
            if (kind == IdentificationTransportKind.BLUETOOTH) require(counts[3] == 1)
            return IdentificationTransportComponent(kind, id, requireNotNull(name), active,
                address, usbHostNumber, carPlay, flag5, identifier6)
        } catch (error: IllegalArgumentException) {
            // No child reason bytes on the wire: reject the containing 17/24 parameter.
            throw IdentificationParameterException(parameterId, error)
        }
    }

    fun capabilities(components: List<IdentificationTransportComponent>, fields: IapFields): CarPlayComponentCapabilities {
        // Other component callbacks can also set CarPlay flags. Until parsed, absence
        // in our two component types is not evidence of absence in the whole message.
        val deferred = fields.ordered.map { it.first }.filter { it in setOf(14, 15, 26) }.toSet()
        val advertised = components.any { it.carPlayFlag }
        val gated = components.filter { it.carPlayFlag && it.flag5 }
        return CarPlayComponentCapabilities(
            carPlay = when {
                advertised -> ComponentCapabilityEvidence.ADVERTISED
                deferred.isNotEmpty() -> ComponentCapabilityEvidence.UNRESOLVED
                else -> ComponentCapabilityEvidence.NOT_ADVERTISED
            },
            wirelessCarPlayAdvertised = components.any {
                it.kind == IdentificationTransportKind.WIRELESS_CARPLAY && it.carPlayFlag
            },
            gatedFlag5 = gated.isNotEmpty(),
            // Last gated component replaces the value, INCLUDING replacement with null.
            gatedIdentifier6 = gated.lastOrNull()?.identifier6,
            deferredTransportParameterIds = deferred,
        )
    }
}
