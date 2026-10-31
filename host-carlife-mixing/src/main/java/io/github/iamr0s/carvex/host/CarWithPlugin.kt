package io.github.iamr0s.carvex.host

import android.content.Context
import io.github.iamr0s.carvex.hostregistry.HostPlugin
import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks
import io.github.iamr0s.carvex.host.vendor.XiaomiHostPlugin

/**
 * Compatibility name for the Xiaomi entry. Availability only checks that the
 * endpoint application is installed; binding failures are handled by the
 * shared mixing host and the orchestration layer.
 */
/**
 * Compatibility name for callers that still refer to the old Xiaomi plugin.
 * The actual implementation is the same configured [VendorCastHost] used by
 * every other CarLife mixing endpoint.
 */
object CarWithPlugin: HostPlugin {
    override val id: String = XiaomiHostPlugin.id
    override val displayName: String = XiaomiHostPlugin.displayName
    override fun isAvailable(context: Context): Boolean = XiaomiHostPlugin.isAvailable(context)
    override fun create(context: Context, callbacks: HostCallbacks): CastHost =
        XiaomiHostPlugin.create(context, callbacks)
}
