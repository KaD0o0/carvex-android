package io.github.iamr0s.carvex.carplay.core

/** Wireless CarPlay (24A446 session kind=5) request subset.
 * The control prefix is followed by a kind=5 early exit: generic timingProtocol,
 * isScreenMirroringSession and combined-info options are not appended. The screen
 * helper also skips automatic timestampInfo (external ExtraStreamOptions are not
 * implemented here). Identity values are still this client's compatibility profile,
 * not a claim that Android implements every 24A446 provider or capability.
 * Original producers: AirPlaySender 0x251a59708, 0x2519bbf24 and screen Resume. */
internal object CarPlaySetupRequests {
    data class Encryption(val type:Int,val wrappedKey:ByteArray,val iv:ByteArray)

    fun control(
        deviceId:String,
        macAddress:String,
        name:String,
        model:String,
        osVersion:String,
        sessionUuid:String,
        /** Port of an actually started local timing provider; zero/null emits no key. */
        timingPort:Int?=null,
        encryption:Encryption?=null,
        /** Independent correlation provider, NOT a copy of sessionUuid. */
        sessionCorrelationUuid:String?=null,
        /** APTransport's property, NOT the iAP2 raw-kind or session kind enum. */
        transportType:Int?=null,
        /** null is no provider result; an explicitly supplied empty array is different. */
        requestedFeatures:List<Any?>?=null
    ):Map<String,Any?> {
        require(timingPort==null || timingPort in 0..65535){"Invalid started timing port"}
        return linkedMapOf<String,Any?>(
            "deviceID" to deviceId,"macAddress" to macAddress,"name" to name
        ).apply {
            putAll(CarPlaySourceIdentity.setupDeviceFields(model,osVersion))
            put("sessionUUID",sessionUuid)
            put("sourceVersion",CarPlaySourceIdentity.sourceVersion)
            put("statsCollectionEnabled",false)
            put("updateSessionRequest",false)
            if(timingPort!=null && timingPort!=0)put("timingPort",timingPort)
            sessionCorrelationUuid?.let{put("sessionCorrelationUUID",it)}
            encryption?.let{put("et",it.type);put("ekey",it.wrappedKey.copyOf());put("eiv",it.iv.copyOf())}
            if(transportType==2)put("keepAliveLowPower",true)
            requestedFeatures?.let{put("features",it.toList())}
        }
    }

    // AirPlaySupport _APSCarPlayScreenLatencyMs: wireless default 75, USB default
    // 25, with Apple preference overrides. We implement the wireless default;
    // this is a declared screen latency, not a measurement of display/audio lag.
    const val wirelessScreenLatencyMs=75

    fun screen(streamConnectionId:Long,displayUuid:String?):Map<String,Any?> {
        // Ordinary CarPlay screen_initDisplayInfo rejects a missing endpoint
        // display UUID (0x251b44d90 -> 0x251bd94ec). Do not invent an aux UUID.
        requireNotNull(displayUuid){"Screen SETUP selected display has no UUID"}
        return linkedMapOf("type" to 110,"streamConnectionID" to streamConnectionId,
            "latencyMs" to wirelessScreenLatencyMs,"uuid" to displayUuid)
    }
}
