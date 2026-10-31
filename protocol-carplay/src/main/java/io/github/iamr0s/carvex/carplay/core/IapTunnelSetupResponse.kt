package io.github.iamr0s.carvex.carplay.core

/** The narrow response contract recovered from AirPlaySender's type130 SETUP path. */
data class IapTunnelSetupReply(val streamId:Long,val dataPort:Int?)

object IapTunnelSetupResponse {
    /**
     * Accept exactly one typed stream. A missing or zero dataPort is a valid
     * shared-carrier result; malformed structure is not a successful response.
     */
    fun parse(response:Map<String,Any?>):IapTunnelSetupReply {
        val streams=response["streams"] as? List<*>
        require(streams!=null){"type130 response missing streams"}
        require(streams.size==1){"type130 response must contain exactly one stream"}
        val stream=streams.single() as? Map<*,*>
        require(stream!=null){"type130 response stream is not a dictionary"}
        require((stream["type"] as? Number)?.toInt()==130){"type130 response stream type mismatch"}
        val streamId=(stream["streamID"] as? Number)?.toLong()
            ?: throw IllegalArgumentException("type130 response missing streamID")
        val dataPort=(stream["dataPort"] as? Number)?.toLong()?.let{
            when {
                it==0L -> null
                it in 1..65535 -> it.toInt()
                else -> throw IllegalArgumentException("type130 response dataPort out of range")
            }
        }
        return IapTunnelSetupReply(streamId,dataPort)
    }
}
