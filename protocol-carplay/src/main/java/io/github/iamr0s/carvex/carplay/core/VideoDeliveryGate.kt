package io.github.iamr0s.carvex.carplay.core

/** Called by the single writer before encryption. Dropping a reference also drops its dependents. */
class VideoDeliveryGate(private val maxAgeMs:Long=150) {
    enum class Decision { SEND, DROP, DROP_REQUEST_KEY }
    private var needsConfig=true
    private var waitingForKey=true
    init{require(maxAgeMs>=0)}
    fun reset(){needsConfig=true;waitingForKey=true}
    fun decide(config:Boolean,key:Boolean,ageMs:Long):Decision {
        if(config){needsConfig=false;waitingForKey=true;return Decision.SEND}
        if(needsConfig)return Decision.DROP
        if(key){waitingForKey=false;return Decision.SEND}
        if(waitingForKey)return Decision.DROP
        if(ageMs>maxAgeMs){waitingForKey=true;return Decision.DROP_REQUEST_KEY}
        return Decision.SEND
    }
}
