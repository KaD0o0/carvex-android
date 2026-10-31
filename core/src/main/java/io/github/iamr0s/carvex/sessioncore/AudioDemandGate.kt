package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.pcm.PcmFormat

/** PCM requests and explicit negotiated bootstrap requests share one setup/ownership gate.
 * A failed category gets one bounded retry after local backoff. Active transports remain owned by
 * the session/HostAudioBridge; a SETUP completing after close is discarded before installation. */
class AudioDemandGate(private val now:()->Long=System::nanoTime,
    private val retryDelayNs:Long=1_000_000_000L,private val maximumAttempts:Int=2):AutoCloseable {
    class Ticket internal constructor(val category:Int,val format:PcmFormat,val attempt:Int,val capturedAtNs:Long)
    private enum class State { PENDING,ACTIVE,FAILED }
    private data class Entry(val ticket:Ticket,var state:State=State.PENDING,
        var failedAtNs:Long=0,var transport:NegotiatedAudioTransport?=null)
    private val entries=mutableMapOf<Int,Entry>()
    private var closed=false
    init{require(retryDelayNs>=0&&maximumAttempts in 1..2)}

    @Synchronized fun claim(category:Int,pcmBytes:Int,format:PcmFormat):Ticket? {
        if(closed||category !in 1..3||!format.accepts(pcmBytes))return null
        return claimSetup(category,format)
    }
    /** Explicit v11 bootstrap, after intersecting receiver capability with local encoders.
     * An empty host callback is still not a demand and cannot use this entry point. */
    @Synchronized fun prime(category:Int,format:PcmFormat):Ticket? {
        if(closed||category !in 1..3)return null
        return claimSetup(category,format)
    }
    private fun claimSetup(category:Int,format:PcmFormat):Ticket? {
        val current=entries[category]
        val time=now()
        if(current!=null){
            if(current.state==State.ACTIVE&&current.transport?.ready!=true){
                current.state=State.FAILED;current.failedAtNs=time
            }
            if(current.state!=State.FAILED||current.ticket.attempt>=maximumAttempts||
                time-current.failedAtNs<retryDelayNs)return null
        }
        val ticket=Ticket(category,format,(current?.ticket?.attempt?:0)+1,time)
        entries[category]=Entry(ticket)
        return ticket
    }

    @Synchronized private fun pending(ticket:Ticket)=!closed&&entries[ticket.category]?.let{
        it.ticket===ticket&&it.state==State.PENDING}==true

    /** The install callback must itself reject a closed downstream owner (HostAudioBridge does).
     * Recheck afterwards so a concurrent close cannot leave an accepted live transport behind. */
    suspend fun install(ticket:Ticket,transport:NegotiatedAudioTransport,
        attach:suspend (NegotiatedAudioTransport)->Unit):Boolean {
        if(!pending(ticket)){transport.close();return false}
        try{
            attach(transport)
            val accepted=synchronized(this){
                if(!pending(ticket))false else {
                    entries.getValue(ticket.category).apply{state=State.ACTIVE;this.transport=transport}
                    true
                }
            }
            if(!accepted)transport.close()
            return accepted
        }catch(t:Throwable){failed(ticket);runCatching{transport.close()};throw t}
    }

    @Synchronized fun failed(ticket:Ticket){
        val entry=entries[ticket.category]
        if(!closed&&entry!=null&&entry.ticket===ticket&&entry.state==State.PENDING){
            entry.state=State.FAILED;entry.failedAtNs=now()
        }
    }
    @Synchronized override fun close(){closed=true;entries.clear()}
}
