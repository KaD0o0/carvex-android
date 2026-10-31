package io.github.iamr0s.carvex.ui.model

import android.content.Context
import com.baidu.carlife.service.CarlifeConnectService
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.Permissions
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.BondedDevices
import io.github.iamr0s.carvex.sessioncore.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Boundary between the DeviceApp-style UI and the real bridge implementation.
 *
 * The old implementation used a delay and random numbers here. The bridge has one real
 * connection entry point today (the Bluetooth/iAP2 session in [CarlifeConnectService]), so the
 * transport deliberately exposes the protocol/carrier mode rather than a raw radio name. Session progress and diagnostics are read
 * from [BridgeStore] by [DeviceViewModel].
 */
interface DeviceTransport {
    suspend fun scan(): List<Device>
    suspend fun connect(device: Device, mode: ConnectionMode)
    suspend fun disconnect(device: Device)
    /** The current bridge does not expose a synthetic ping operation. */
    suspend fun ping(device: Device): Int = error("Session ping is not available")
}

class RealDeviceTransport(private val context: Context) : DeviceTransport {
    companion object {
        /** Modes backed by the current CarPlay bootstrap adapter. */
        val supportedModes: Set<ConnectionMode> = setOf(ConnectionMode.CARPLAY_BLUETOOTH)

        /** Compatibility view used by the existing settings UI. */
        val supportedMethods: Set<Method> = Method.entries.filterTo(linkedSetOf()) { it.mode in supportedModes }
    }

    override suspend fun scan(): List<Device> = withContext(Dispatchers.IO) {
        BondedDevices.list(context).map { paired ->
            Device(
                id = paired.address.uppercase().hashCode(),
                name = paired.name,
                signal = Signal.Unknown,
                address = paired.address.uppercase(),
            )
        }
    }

    override suspend fun connect(device: Device, mode: ConnectionMode) {
        check(mode in supportedModes) {
            "${mode.displayName} is not supported by the active bridge session"
        }
        check(Permissions.granted(context)) { context.getString(R.string.required_permission_error) }
        withContext(Dispatchers.IO) {
            // SessionController reads this target when the service starts. The service itself
            // owns the real Bluetooth/iAP2 handshake and foreground lifecycle.
            BridgeStore.configure(BridgeStore.options.value.copy(target = device.address.uppercase()))
            CarlifeConnectService.connect(context)
        }
    }

    override suspend fun disconnect(device: Device) = withContext(Dispatchers.IO) {
        CarlifeConnectService.disconnect(context)
    }
}
