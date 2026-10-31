package io.github.iamr0s.carvex.carplay.core
/**
 * Device authentication: the mirror direction of the accessory chain.
 *
 * In `0xaa00`..`0xaa06` the accessory proves itself to this client. The mirror chain is
 * `0xaa10`..`0xaa15`: the accessory asks this client for its own certificate (`0xaa11`) and
 * challenge response (`0xaa13`). This client has no provisioned device certificate, so the
 * capability is skipped by default: nothing is advertised and nothing is sent proactively.
 *
 * If an accessory insists and actually requests `0xaa10`/`0xaa12`, the request is not answered
 * with a fabricated certificate — it fails loudly instead. A silent ignore looks like a hang, and
 * an empty or invented response can be mistaken for a successful authentication.
 *
 * [provideCertificate] is the single switch for a future real implementation.
 */
object DeviceAuthPolicy {
    const val REQUEST_DEVICE_CERTIFICATE=0xaa10
    const val REQUEST_DEVICE_CHALLENGE_RESPONSE=0xaa12
    const val DEVICE_AUTHENTICATION_FAILED=0xaa14
    const val DEVICE_AUTHENTICATION_SUCCEEDED=0xaa15

    val requestIds=setOf(REQUEST_DEVICE_CERTIFICATE,REQUEST_DEVICE_CHALLENGE_RESPONSE)

    @Volatile var provideCertificate:Boolean=false

    fun isRequest(id:Int):Boolean=id in requestIds

    fun configure(enabled:Boolean){provideCertificate=enabled}

    /** Non-null means "cannot serve this request"; the caller must fail instead of answering. */
    fun refusal(id:Int):String?=when{
        id !in requestIds -> "not a device authentication request"
        provideCertificate -> "device authentication is enabled but no certificate backend is wired yet"
        else -> "accessory requested device authentication (0x%%04x) but this client has no device certificate".format(id)
    }
}
