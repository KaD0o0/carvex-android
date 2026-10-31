package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire

/**
 * 24A446 1D06 is an identity delta, not another initial IdentificationInfo.
 * Only 0..5 produce replacement strings. In particular, 6/7 do not replace
 * message declarations and 34 does not produce a PPID update in this handler.
 */
object IapIdentificationUpdate {
    fun apply(current: IdentifyInfo, fields: IapFields): IdentifyInfo {
        val replacements = arrayOfNulls<String>(6)
        for ((id, bytes) in fields.ordered) {
            if (id !in 0..5) continue
            // The original CString getter requires a value byte and a final NUL.
            // It permits an empty CString and does not forbid an earlier NUL.
            // JVM UTF-8 conversion is retained; full CF conversion parity is OPEN.
            if (bytes.isEmpty() || bytes.last() != 0.toByte()) {
                throw IdentificationParameterException(id,
                    IllegalArgumentException("Identification update CString requires final NUL"))
            }
            replacements[id] = IapWire.text(bytes)
        }
        // Commit only after the entire ordered message succeeds. A later invalid
        // occurrence cannot be hidden by a valid repeat, nor publish a partial delta.
        // rawParameters remains the initial declaration evidence, not a merged wire message.
        return current.copy(
            name = replacements[0] ?: current.name,
            modelIdentifier = replacements[1] ?: current.modelIdentifier,
            manufacturer = replacements[2] ?: current.manufacturer,
            serialNumber = replacements[3] ?: current.serialNumber,
            firmwareVersion = replacements[4] ?: current.firmwareVersion,
            hardwareVersion = replacements[5] ?: current.hardwareVersion,
        )
    }
}
