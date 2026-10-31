package io.github.iamr0s.carvex.carplay.core
/** Local declaration retained for this client's compatibility subset, not a receiver version probe.
 * Change with a verified request/response matrix; Android hardware/OS fields are reported separately. */
object CarPlaySourceIdentity {
    const val sourceVersion="320.17"
    val userAgent get()="AirPlay/"+sourceVersion

    /** Wire compatibility declaration, NOT the Android OS build or an iOS identity.
     * Toyota libAirPlay's main/alt receiver compares this with "13A1" (0x1af74).
     * An unparsable Android Build.ID selects full-header AAD, but our established
     * HomeKit audio writers use timestamp+SSRC (RTP bytes 4..11), like 24A446.
     * 21A329 selects that modern branch (strictly newer than 13A1). Keep hardware,
     * osName and osVersion truthful; report the actual Android build in diagnostics.
     * This field can affect other receiver compatibility decisions as well. */
    const val compatibilityOsBuildVersion="21A329"

    fun setupDeviceFields(model:String,osVersion:String):Map<String,Any> = linkedMapOf(
        "model" to model,"osBuildVersion" to compatibilityOsBuildVersion,
        "osVersion" to osVersion,"osName" to "Android")
}

/** Local control-protocol declaration, independent of sourceVersion and osBuildVersion.
 * 24A446 APTransport 0x24fda6360 adds this header for internal stup/info requests.
 * APSSystemGetUserVersion can return 0/1/2; that does not establish our support for
 * all three profiles. Retain this client's existing value 1 consistently. */
internal object CarPlayControlProfile {
    const val protocolVersionHeader="X-Apple-ProtocolVersion"
    const val protocolVersion="1"

    /** SETUP covers control/update/media requests. Only the current exact GET /info
     * route is mapped to Apple's info operation; query/absolute URL variants remain
     * ordinary requests without an automatic declaration, not rejected requests. */
    fun versionFor(method:String,path:String):String? =
        if(method=="SETUP"||(method=="GET"&&path=="/info"))protocolVersion else null
}
