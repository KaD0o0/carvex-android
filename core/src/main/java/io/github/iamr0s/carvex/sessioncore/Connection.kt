package io.github.iamr0s.carvex.sessioncore

import java.io.Closeable
import io.github.iamr0s.carvex.carplay.core.IapControlSessionKind

/**
 * A connection is identified by the protocol and the carrier together.
 *
 * Keeping these two dimensions in one value prevents a raw Bluetooth/Wi-Fi/USB
 * label from becoming an accidental protocol selector.  A future CarLife or
 * HiCar adapter can use the same UI and session contracts without changing the
 * transport-neutral code.
 */
enum class ProtocolKind(val id: String, val title: String) {
    CARPLAY("carplay", "CarPlay"),
    CARLIFE("carlife", "CarLife"),
    HICAR("hicar", "HiCar"),
}

enum class CarrierKind(val id: String, val title: String) {
    BLUETOOTH("bluetooth", "Bluetooth"),
    WIFI("wifi", "WIFI"),
    USB("usb", "USB"),
    TUNNEL("tunnel", "Tunnel"),
}

data class ConnectionMode(
    val protocol: ProtocolKind,
    val carrier: CarrierKind,
) {
    val id: String get() = "${protocol.id}-${carrier.id}"
    val displayName: String get() = "${protocol.title}-${carrier.title}"

    companion object {
        val CARPLAY_BLUETOOTH = ConnectionMode(ProtocolKind.CARPLAY, CarrierKind.BLUETOOTH)
        val CARPLAY_WIFI = ConnectionMode(ProtocolKind.CARPLAY, CarrierKind.WIFI)
        val CARPLAY_USB = ConnectionMode(ProtocolKind.CARPLAY, CarrierKind.USB)
        val CARPLAY_TUNNEL = ConnectionMode(ProtocolKind.CARPLAY, CarrierKind.TUNNEL)

        fun parse(id: String): ConnectionMode? {
            val (protocolId, carrierId) = id.lowercase().split('-', limit = 2)
                .takeIf { it.size == 2 } ?: return null
            val protocol = ProtocolKind.entries.firstOrNull { it.id == protocolId } ?: return null
            val carrier = CarrierKind.entries.firstOrNull { it.id == carrierId } ?: return null
            return ConnectionMode(protocol, carrier)
        }

    }
}

/** Platform-independent byte stream boundary used by iAP2 and AirPlay. */
interface ByteStream : Closeable {
    suspend fun read(maxBytes: Int, deadlineNanos: Long): ByteArray
    suspend fun write(bytes: ByteArray, deadlineNanos: Long)
}

enum class TransportErrorCategory {
    RADIO_UNAVAILABLE,
    PERMISSION_DENIED,
    SERVICE_NOT_FOUND,
    CONNECT_TIMEOUT,
    NETWORK_NOT_ASSOCIATED,
    ADDRESS_UNUSABLE,
    STREAM_EOF,
    PLATFORM_UNSUPPORTED,
    READ_TIMEOUT, WRITE_TIMEOUT, STREAM_CLOSED, CANCELLED, STALE_GENERATION,
    SCHEMA_UNSUPPORTED, SCHEMA_AMBIGUOUS, INTERFACE_BIND_FAILED,
    DESCRIPTOR_INVALID, ENDPOINT_UNAVAILABLE, CONTROL_LENGTH_MISMATCH, CONTROL_PHASE_INVALID,
    FRAME_TOO_LARGE, BUFFER_LIMIT, TRANSPORT_FRAMING_INVALID, ROLE_CHANGE_TIMEOUT,
    TRANSPORT_UNAVAILABLE,
    DISCOVERY_FAILED,
    LINK_FAILED,
    IDENTIFICATION_REJECTED,
    AUTH_FAILED,
    HANDOFF_FAILED,
    AIRPLAY_REJECTED,
    MEDIA_FAILED,
    SESSION_EOF,
}

class TransportException(
    val category: TransportErrorCategory,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Adapter contract. Android Bluetooth/USB/Network APIs belong behind this interface. */
interface TransportAdapter {
    val mode: ConnectionMode
    suspend fun discover(): List<PeerEndpoint>
    suspend fun open(endpoint: PeerEndpoint, deadlineNanos: Long): ByteStream
}

data class PeerEndpoint(
    val id: String,
    val displayName: String,
    val address: String? = null,
    val port: Int? = null,
)

/** Protocol session contract; it deliberately knows nothing about Android hosts. */
interface ProtocolSession {
    val protocol: ProtocolKind
    suspend fun start(stream: ByteStream, endpoint: PeerEndpoint)
    suspend fun stop()
}

enum class LinkTransport { USB, BT_RFCOMM, WIFI_TCP, TUNNEL }
enum class ConnectionDirection { INBOUND, OUTBOUND }
enum class LinkInitiator { HOST, ACC, OBSERVED }
enum class SyncMode { HOST_FIRST, ACC_FIRST, OBSERVED }
enum class AuthState { IDLE, REQUESTED, VERIFIED, EXPERIMENTAL_INTEGRITY, FAILED }
/** Local transaction states, not the numeric states carried inside Apple's implementation. */
enum class IdentificationState { IDLE, STARTING, STARTED, RETRY_PENDING, ACCEPTING, ACCEPTED, REJECTED, CANCELLED }
enum class FirstPeerEvent { AA_01, AA_03, ID_01, ID_03, NONE }
enum class BootstrapRole { USB, BLUETOOTH, TUNNEL }
enum class AvailabilityShape { DEFAULT, THREE_GROUP, MINIMAL, PEER_SPECIFIC }
enum class WifiRequestPolicy { REQUIRED, OPTIONAL, SUPPRESSED }
enum class FallbackAttempt { NONE, AUTH_FIRST, IDENT_FIRST }

data class MessageId(val group: Int, val message: Int) {
    init {
        require(group in 0..0xff && message in 0..0xff)
    }

    val value: Int get() = (group shl 8) or message
}

/** Runtime state assembled from the peer's first event and IdentifyInfo. */
data class PeerNegotiationContext(
    val transport: LinkTransport,
    val direction: ConnectionDirection,
    val linkInitiator: LinkInitiator = LinkInitiator.OBSERVED,
    val syncMode: SyncMode = SyncMode.OBSERVED,
    val authState: AuthState = AuthState.IDLE,
    val identificationState: IdentificationState = IdentificationState.IDLE,
    val firstPeerEvent: FirstPeerEvent = FirstPeerEvent.NONE,
    val modern: Boolean? = null,
    val accessoryReceives: Set<MessageId> = emptySet(),
    val accessorySends: Set<MessageId> = emptySet(),
    val bootstrapRole: BootstrapRole,
    val availabilityShape: AvailabilityShape = AvailabilityShape.DEFAULT,
    val wifiRequestPolicy: WifiRequestPolicy = WifiRequestPolicy.OPTIONAL,
    val fallbackAttempt: FallbackAttempt = FallbackAttempt.NONE,
    val controlSessionId: Int? = null,
    val controlSessionVersion: Int? = null,
    val controlSessionKind: IapControlSessionKind = IapControlSessionKind.OTHER,
) {
    init {
        require(controlSessionId == null || controlSessionId in 1..255)
        require(controlSessionVersion == null || controlSessionVersion in 0..255)
    }

    fun allowsHostMessage(id: MessageId): Boolean = id in accessoryReceives
    fun peerMaySend(id: MessageId): Boolean = id in accessorySends
}

enum class IdentificationTransportKind(val parameterId: Int, val carrier: LinkTransport) {
    BLUETOOTH(17, LinkTransport.BT_RFCOMM),
    USB_HOST(16, LinkTransport.USB),
    WIRELESS_CARPLAY(24, LinkTransport.WIFI_TCP),
}

/** Parsed component declaration, not proof of an active/authorized transport. */
data class IdentificationTransportComponent(
    val kind: IdentificationTransportKind,
    val id: Int,
    val name: String,
    val active: Boolean = false,
    val bluetoothAddress: List<Int>? = null,
    val usbHostNumber: Int? = null,
    val carPlayFlag: Boolean = false,
    // Preserve these fields without guessing their downstream business meaning.
    val flag5: Boolean = false,
    val identifier6: String? = null,
)

enum class ComponentCapabilityEvidence { ADVERTISED, NOT_ADVERTISED, UNRESOLVED }

data class CarPlayComponentCapabilities(
    val carPlay: ComponentCapabilityEvidence = ComponentCapabilityEvidence.UNRESOLVED,
    val wirelessCarPlayAdvertised: Boolean = false,
    val gatedFlag5: Boolean = false,
    val gatedIdentifier6: String? = null,
    val deferredTransportParameterIds: Set<Int> = emptySet(),
)

/** Implemented request/response routes only; not network readiness or authorization. */
data class NetworkHandoffCapabilities(
    val wifiAnnouncement: Boolean = false,
    val carPlayStartSession: Boolean = false,
) {
    val hasSupportedRoute: Boolean get() = wifiAnnouncement || carPlayStartSession
}

/** IdentifyInfo data is retained losslessly enough for profile-specific parsing. */
data class IdentifyInfo(
    val name: String? = null,
    val modelIdentifier: String? = null,
    val manufacturer: String? = null,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val accessorySends: List<MessageId> = emptyList(),
    val accessoryReceives: List<MessageId> = emptyList(),
    /** 24A446 IdentificationInfo field 8; null means the field was absent. */
    val powerProvidingCapability: Int? = null,
    /** 24A446 IdentificationInfo field 9; null means the field was absent. */
    val maximumCurrentDrawnFromDevice: Int? = null,
    val rawParameters: List<RawParameter> = emptyList(),
    val currentLanguage: String? = null,
    val supportedLanguages: List<String> = emptyList(),
    val ppid: String? = null,
    val components: List<IdentificationTransportComponent> = emptyList(),
    val componentCapabilities: CarPlayComponentCapabilities = CarPlayComponentCapabilities(),
    val validatedMessageFeatures: Set<Int> = emptySet(),
    val networkHandoff: NetworkHandoffCapabilities = NetworkHandoffCapabilities(),
)

data class RawParameter(val id: Int, val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RawParameter && id == other.id && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * id + value.contentHashCode()
}

data class CapabilityRegistry(
    val receivedByAccessory: Set<MessageId> = emptySet(),
    val sentByAccessory: Set<MessageId> = emptySet(),
    val transportComponents: Set<LinkTransport> = emptySet(),
    val language: String? = null,
    val version: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val malformedFields: List<RawParameter> = emptyList(),
    val componentCapabilities: CarPlayComponentCapabilities = CarPlayComponentCapabilities(),
    val networkHandoff: NetworkHandoffCapabilities = NetworkHandoffCapabilities(),
) {
    fun canSend(id: MessageId): Boolean = id in receivedByAccessory
    fun canReceive(id: MessageId): Boolean = id in sentByAccessory
}
