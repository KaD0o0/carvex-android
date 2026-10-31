package io.github.iamr0s.carvex.carplay.core

/** Existing SETUP field baseline retained during the session build/AAD repair.
 * This is not a complete reproduction of 24A446: its 100/101 paths do not create
 * the output control transport used by 102. That separation needs its own change
 * and vehicle validation, independent of the session identity correction. */
object AudioSetupDescription {
    fun playback(route:CarPlayAudioPlan.Route,format:CarPlayAudioPlan.Format,connectionId:Long,
        controlPort:Int,inputPort:Int?=null,offerRedundancy:Boolean=route.type!=102):Map<String,Any> {
        require(controlPort in 1..65535)
        require(inputPort==null||inputPort in 1..65535)
        return linkedMapOf<String,Any>(
            "type" to route.type,"audioType" to route.purpose,"audioFormat" to format.bit,
            "streamConnectionID" to connectionId,"audioLatencyMs" to 100,
            "controlPort" to controlPort,
            // Preserve the existing 5 ms PCM declaration for this isolated repair.
            "spf" to if(format.codec==CarPlayAudioPlan.Codec.PCM16)format.rate/200 else format.framesPerPacket,
            "supportsHighAccuracyTimestamps" to true
        ).apply {
            // 24A446 only offers RED on eligible wireless raw100/raw101 streams.
            // A future USB caller must pass false explicitly.
            if(offerRedundancy)put("supportsRTPPacketRedundancy",true)
            if(inputPort!=null){put("input",true);put("dataPort",inputPort)}
        }
    }
}
