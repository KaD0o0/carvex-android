package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire

/** A failed callback can append more than one empty rejection TLV for the same ID. */
class IdentificationParameterException(
    val parameterId: Int,
    cause: Throwable,
    val rejectedParameterIds: List<Int> = listOf(parameterId),
) : IllegalArgumentException("Invalid identification parameter $parameterId", cause)

/** Evaluated only after all implemented callbacks succeeded, never before parsing. */
class IdentificationMissingParametersException(val parameterIds: List<Int>) :
    IllegalArgumentException("Missing identification parameters $parameterIds")

/**
 * Bounded initial 1D01 parser for the 24A446 registration profile. This does not
 * implement all feature/transport/power validators. It is not the 1D06 consumer.
 */
object IapIdentifyInfo {
    // Recovered from 29 feature descriptors in 24A446 accessoryd (34 records).
    // Registered is not validated or supported: 19 explicitly rejects below.
    private val registeredIds = (0..26).toSet() + setOf(29, 30, 33, 34, 38, 240, 241)
    private val parsedIds = (0..9).toSet() + setOf(12, 13, 16, 17, 24, 34)
    // Power 8/9 are also required by Apple, but cannot be marked successful
    // until the separate endpoint/provider contract is implemented.
    private val requiredCoreIds = (0..7).toList() + listOf(12, 13)

    fun deferredParameterIds(fields: IapFields): Set<Int> = fields.ordered
        .map { it.first }.filter { it in registeredIds && it !in parsedIds && it != 19 }.toSet()

    // Parser-only callers can omit endpoint context. Acceptance callers supply the
    // locally required transport so missing core AND transport fields accumulate.
    fun parse(fields: IapFields, requiredTransport: IdentificationTransportKind? = null): IdentifyInfo {
        fun reject(id: Int, reason: String, repeats: Int = 1): Nothing {
            throw IdentificationParameterException(id, IllegalArgumentException(reason), List(repeats) { id })
        }
        fun text(id: Int, bytes: ByteArray): String {
            // CString getter 0x100067ee4 checks final NUL, not just any NUL.
            // A malformed 13 appends once in processStringParam and again in
            // the supported-language caller; ordinary fields append once.
            if (bytes.isEmpty() || bytes.last() != 0.toByte()) {
                reject(id, "CString requires final NUL", if (id == 13) 2 else 1)
            }
            // Keep the existing JVM UTF-8 decoder. CF conversion/NULL and
            // Unicode equality parity remain unproven; no invented ASCII cap.
            return IapWire.text(bytes)
        }
        fun messages(id: Int, bytes: ByteArray): List<MessageId> {
            if (bytes.size % 2 != 0) reject(id, "Message list requires BE16 elements")
            return (bytes.indices step 2).map {
                MessageId(bytes[it].toInt() and 255, bytes[it + 1].toInt() and 255)
            }.distinct() // Set semantics; stable first-seen order is local diagnostics only.
        }
        val strings = arrayOfNulls<String>(6)
        var currentLanguage: String? = null
        val supportedLanguages = linkedSetOf<String>()
        var ppid: String? = null
        var powerProvidingCapability: Int? = null
        var maximumCurrentDrawnFromDevice: Int? = null
        var sends: List<MessageId> = emptyList()
        var receives: List<MessageId> = emptyList()
        val components = mutableListOf<IdentificationTransportComponent>()
        val successful = linkedSetOf<Int>()
        // No singleton accessor: field order, repeated replacement and the FIRST
        // failing callback matter. Nothing is published until the whole parse succeeds.
        for ((id, bytes) in fields.ordered) {
            when (id) {
                in 0..5 -> strings[id] = text(id, bytes)
                6 -> sends = messages(id, bytes)
                7 -> receives = messages(id, bytes)
                8 -> {
                    if (bytes.size != 1) reject(id, "Power capability requires U8")
                    powerProvidingCapability = bytes[0].toInt() and 255
                }
                9 -> {
                    if (bytes.size != 2) reject(id, "Maximum current requires U16BE")
                    maximumCurrentDrawnFromDevice = IapWire.u16(bytes, 0)
                }
                12 -> currentLanguage = text(id, bytes)
                13 -> {
                    val language = text(id, bytes)
                    if (!supportedLanguages.add(language)) reject(id, "Duplicate supported language")
                }
                16, 17, 24 -> {
                    val component = IapIdentificationTransport.parse(id, bytes)
                    if (components.any { it.id == component.id }) reject(id, "Duplicate component ID")
                    components += component
                }
                34 -> ppid = text(id, bytes)
                19 -> reject(id, "Reserved identification parameter")
                else -> {
                    if (id !in registeredIds) reject(id, "Unregistered 24A446 identification parameter")
                    // Deferred feature input is retained as raw evidence, NEVER
                    // counted as successfully validated by this bounded parser.
                    continue
                }
            }
            successful += id
        }
        val missing = requiredCoreIds.filter { it !in successful } +
            if (requiredTransport != null && components.none { it.kind == requiredTransport })
                listOf(requiredTransport.parameterId) else emptyList()
        if (missing.isNotEmpty()) throw IdentificationMissingParametersException(missing)
        return IdentifyInfo(
            name = strings[0], modelIdentifier = strings[1], manufacturer = strings[2],
            serialNumber = strings[3], firmwareVersion = strings[4], hardwareVersion = strings[5],
            accessorySends = sends, accessoryReceives = receives,
            powerProvidingCapability = powerProvidingCapability,
            maximumCurrentDrawnFromDevice = maximumCurrentDrawnFromDevice,
            rawParameters = fields.ordered.map { RawParameter(it.first, it.second.copyOf()) },
            currentLanguage = currentLanguage, supportedLanguages = supportedLanguages.toList(), ppid = ppid,
            components = components.toList(),
            componentCapabilities = IapIdentificationTransport.capabilities(components, fields),
        )
    }
}
