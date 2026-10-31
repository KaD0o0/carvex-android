package io.github.iamr0s.carvex.host.vendor

import io.github.iamr0s.carvex.sessioncore.HostKeyMapper
import io.github.iamr0s.carvex.host.MixingKeyMapper

/** Runtime description of a phone-to-car vendor endpoint. */
data class VendorHostConfig(
    val id: String,
    val displayName: String,
    val hostPackage: String,
    val serviceClass: String,
    val serviceAction: String? = null,
    val protocolType: Int? = null,
    /** Optional host-side semantic key map. CarLife transport itself does not require one. */
    val keyMapper: HostKeyMapper? = MixingKeyMapper(),
    /** Some firmware exposes a second class during an update. */
    val fallbackServiceClasses: List<String> = emptyList(),
)
