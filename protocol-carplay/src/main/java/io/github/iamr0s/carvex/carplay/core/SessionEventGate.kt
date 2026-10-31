package io.github.iamr0s.carvex.carplay.core

/** Business callbacks belong to a verified, negotiated control session, never to pairing traffic. */
class SessionEventGate(private val binding:SecurityBinding,private val current:()->Boolean) {
    private var authenticated=false
    private var negotiated=false
    private var closed=false
    @Synchronized fun requireCurrent(){
        if(closed||!current())securityFailure(SecurityReason.STALE_CONTEXT,phase="event-context")
    }
    @Synchronized fun authenticated(actual:SecurityBinding){
        requireCurrent()
        if(actual!=binding)securityFailure(SecurityReason.STALE_CONTEXT,phase="event-context")
        authenticated=true
    }
    @Synchronized fun negotiated(actual:SecurityBinding){
        requireCurrent()
        if(actual!=binding||!authenticated)securityFailure(SecurityReason.STALE_CONTEXT,phase="event-context")
        negotiated=true
    }
    @Synchronized fun close(){closed=true;authenticated=false;negotiated=false}
    @Synchronized fun dispatch(callback:()->Pair<Int,ByteArray>):Pair<Int,ByteArray> {
        if(closed||!current()||!authenticated)return 403 to byteArrayOf()
        if(!negotiated)return 503 to byteArrayOf()
        return callback()
    }
}
