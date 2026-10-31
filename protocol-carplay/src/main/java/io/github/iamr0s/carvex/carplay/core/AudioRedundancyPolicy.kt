package io.github.iamr0s.carvex.carplay.core

/** One RED decision for SETUP and both output packetizers. */
object AudioRedundancyPolicy {
    private fun eligible(route:CarPlayAudioPlan.Route)=route.type in setOf(100,101,106)

    /** Offer supported routes; only the validated peer reply enables RED.
     * No user diagnostic setting overrides navigation/assistant negotiation. */
    fun offerFor(route:CarPlayAudioPlan.Route):Boolean = eligible(route)

    /** The peer selects RED only within the local offer. Do not silently send plain
     * RTP after a conflicting successful SETUP.
     * The caller must tear down that stream before constructing an output writer.
     * raw102 remains excluded even if a peer advertises RED on it. */
    fun responseEnables(route:CarPlayAudioPlan.Route,response:Map<*,*>,offered:Boolean):Boolean {
        if(!eligible(route))return false
        val enabled=response["supportsRTPPacketRedundancy"]==true
        if(enabled&&!offered)throw UnsupportedOperationException(
            "Receiver enabled RED without a local offer; refusing mismatched audio packetization")
        return enabled
    }
}
