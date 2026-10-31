package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.carplay.core.IapWire

/** Selected by the local entry point, never by peer fields, brand, active bit or TCP alone. */
enum class IdentificationEndpointContext(
    val requiredComponent: IdentificationTransportKind,
    /** Endpoint raw transport kind used by 24A446's Power field 8 branch. */
    val rawTransportKind: Int,
    /** 24A446's bounded ID9 provider for this endpoint context. */
    val maximumCurrentProvider: Int = 100,
) {
    BLUETOOTH_RFCOMM(IdentificationTransportKind.BLUETOOTH, rawTransportKind = 2),
    AIRPLAY_IAP(IdentificationTransportKind.WIRELESS_CARPLAY, rawTransportKind = 4),
}

class IdentificationMessageValidationException(val rejectedParameters: List<RawParameter>) :
    IllegalArgumentException("Invalid identification message declarations")

/** Acceptance-time checks, kept separate from ordered field parsing and handoff readiness. */
object IapIdentificationValidation {
    private data class Rule(val id: Int, val direction: Int)
    private val wifiInfo = listOf(Rule(0x5700, 0), Rule(0x5701, 1))
    private val wifiHandoff = listOf(Rule(0x5702, 1), Rule(0x5703, 0))
    private val carPlay = listOf(Rule(0x4300, 1), Rule(0x4301, 0))
    private val ownedIds = (wifiInfo + wifiHandoff + carPlay).map { it.id }.toSet()
    private enum class Group { ABSENT, COMPLETE, INCOMPLETE }

    fun validate(info: IdentifyInfo, context: IdentificationEndpointContext): IdentifyInfo {
        // 0x10013bc5c checks the post-callback success set. The original
        // registration marks 8/9 required; absence is not a zero value.
        val missingPower = buildList {
            if (info.powerProvidingCapability == null) add(8)
            if (info.maximumCurrentDrawnFromDevice == null) add(9)
        }
        if (missingPower.isNotEmpty()) throw IdentificationMissingParametersException(missingPower)
        // 0x1000c07a8: the value-1 rejection is limited to raw kinds 0/8.
        if (info.powerProvidingCapability == 1 && context.rawTransportKind in setOf(0, 8)) {
            throw IdentificationParameterException(8, IllegalArgumentException(
                "Power capability 1 is invalid on raw transport ${context.rawTransportKind}"))
        }
        // 0x1000c08a4: U16 value must not exceed the endpoint-context provider.
        if (requireNotNull(info.maximumCurrentDrawnFromDevice) > context.maximumCurrentProvider) {
            throw IdentificationParameterException(9, IllegalArgumentException(
                "Maximum current exceeds provider ${context.maximumCurrentProvider}"))
        }
        if (info.components.none { it.kind == context.requiredComponent }) {
            throw IdentificationMissingParametersException(listOf(context.requiredComponent.parameterId))
        }
        val declared = listOf(info.accessorySends.map { it.value }.toSet(), info.accessoryReceives.map { it.value }.toSet())
        val accounted = listOf(mutableSetOf<Int>(), mutableSetOf<Int>())
        val rejected = BooleanArray(2)
        fun group(rules: List<Rule>): Group {
            if (rules.none { it.id in declared[it.direction] }) return Group.ABSENT
            var complete = true
            for (rule in rules) {
                if (rule.id in declared[rule.direction]) accounted[rule.direction] += rule.id
                else {
                    // Native reason2: mark the direction, but append NO missing ID bytes.
                    rejected[rule.direction] = true
                    complete = false
                }
            }
            return if (complete) Group.COMPLETE else Group.INCOMPLETE
        }
        // Native feature22 short-circuits its SECOND group only. Feature26 still runs.
        val infoGroup = group(wifiInfo)
        val handoffGroup = if (infoGroup == Group.INCOMPLETE) Group.ABSENT else group(wifiHandoff)
        val carPlayGroup = group(carPlay)
        val reasons = (0..1).map { direction ->
            // Bounded ownership pass: do NOT reject other features' IDs merely because
            // their validators are not implemented here. Reverse-direction owned IDs
            // and declared IDs in a skipped group remain unaccounted (native reason1).
            val unaccounted = declared[direction].filter { it in ownedIds && it !in accounted[direction] }
            if (unaccounted.isNotEmpty()) rejected[direction] = true
            RawParameter(6 + direction, encodeIds(unaccounted))
        }
        if (rejected.any { it }) throw IdentificationMessageValidationException(reasons.filterIndexed { i, _ -> rejected[i] })
        val features = buildSet {
            if (infoGroup == Group.COMPLETE || handoffGroup == Group.COMPLETE) add(22)
            if (carPlayGroup == Group.COMPLETE) add(26)
        }
        return info.copy(validatedMessageFeatures = features,
            networkHandoff = NetworkHandoffCapabilities(handoffGroup == Group.COMPLETE, carPlayGroup == Group.COMPLETE))
    }

    private fun encodeIds(ids: List<Int>): ByteArray =
        ids.flatMap { IapWire.be16(it).toList() }.toByteArray()
}
