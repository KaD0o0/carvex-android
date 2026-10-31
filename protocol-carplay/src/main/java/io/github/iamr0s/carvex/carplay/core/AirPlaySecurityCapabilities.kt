package io.github.iamr0s.carvex.carplay.core

/**
 * Bounded 24A446 endpoint capability view, not an authorization or a pairing implementation.
 * AirPlaySender 0x2519bd6fc tests feature IDs 38, 46, 43, then 48 for CU support.
 * The property dispatcher 0x25199a588 separately maps legacy=27 and unified-MFi=51.
 *
 * Extended evidence takes priority over the backwards-compatible low-64 feature mask.
 * Missing/malformed evidence stays unknown; failed discovery never authorizes a weaker exchange.
 */
data class AirPlaySecurityCapabilities(val features: ULong?, val featureEvidence: AirPlayFeatureEvidence? = null) {
    private val evidence get() = featureEvidence ?: AirPlayFeatureEvidence.fromLegacyFlags(features)
    private fun state(bit: Int): Boolean? = evidence.has(bit)

    val directCoreUtilsPairing: Boolean? get() = state(38)
    val hkPairingAndAccessControl: Boolean? get() = state(46)
    val systemPairing: Boolean? get() = state(43)
    val transientPairing: Boolean? get() = state(48)
    val legacyPairing: Boolean? get() = state(27)
    val unifiedPairingAndMfi: Boolean? get() = state(51)
    val mutualMfiAuthentication: Boolean? get() = state(80)

    val coreUtilsPairingAndEncryption: Boolean?
        get() = evidence.set?.let {
            directCoreUtilsPairing == true || hkPairingAndAccessControl == true ||
                systemPairing == true || transientPairing == true
        }

    /** These CU-only entry mechanisms are not implemented by the current bit-38 policy. */
    val requiresUnsupportedCuEntry: Boolean
        get() = coreUtilsPairingAndEncryption == true && directCoreUtilsPairing != true

    /** Capability values only: no identity, key, TXT content, or trust conclusion. */
    fun diagnostic(): String = "cu=$coreUtilsPairingAndEncryption directCu=$directCoreUtilsPairing " +
        "hkAccess=$hkPairingAndAccessControl system=$systemPairing transient=$transientPairing " +
        "legacy=$legacyPairing unifiedMfi=$unifiedPairingAndMfi mutualMfi=$mutualMfiAuthentication " + evidence.diagnostic()
}
