package io.github.iamr0s.carvex.carplay.core

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/** Local stream identities and a type-wide teardown barrier.
 * TEARDOWN carries only type, so no replacement (even a different purpose) may be installed until
 * the previous teardown is acknowledged. An unconfirmed close poisons that type for this session.
 * Existing concurrent open purposes are preserved for capability negotiation; this gate does not
 * claim the receiver isolates such streams when its teardown descriptor contains only type. */
class AudioStreamLifecycle : AutoCloseable {
    class Lease internal constructor(val type:Int,val purpose:String,val id:Long) {
        internal var closing=false
        internal val completion=CompletableDeferred<Unit>()
    }
    private val leases=mutableMapOf<Pair<Int,String>,Lease>()
    private var closed=false

    @Synchronized fun contains(type:Int,purpose:String)=leases.containsKey(type to purpose)
    @Synchronized fun isActive(type:Int,purpose:String)=leases[type to purpose]?.closing==false

    suspend fun reserve(type:Int,purpose:String,id:Long):Lease {
        while(true){
            val closing=synchronized(this){
                check(!closed){"Audio session closed"}
                val pending=leases.values.filter{it.type==type&&it.closing}
                if(pending.isEmpty()){
                    check(!leases.containsKey(type to purpose)){"Audio stream type=$type/$purpose already has a session"}
                    return Lease(type,purpose,id).also{leases[type to purpose]=it}
                }
                pending
            }
            // Suspension is a wait for the previous attempt, not a failed replacement attempt.
            closing.forEach{it.completion.await()}
        }
    }

    /** Called synchronously by close, before dispatching its network cleanup. */
    @Synchronized fun beginClose(lease:Lease):Boolean {
        if(leases[lease.type to lease.purpose]!==lease||lease.closing)return false
        lease.closing=true
        return true
    }

    @Synchronized fun completeClose(lease:Lease,failure:Throwable?=null) {
        if(leases[lease.type to lease.purpose]!==lease)return
        check(lease.closing){"Audio close was not started"}
        if(lease.completion.isCompleted)return
        if(failure==null){
            leases.remove(lease.type to lease.purpose)
            lease.completion.complete(Unit)
        }else{
            // Keep the tombstone: an old type-only TEARDOWN may still reach the receiver later.
            lease.completion.completeExceptionally(IOException("Audio type=${lease.type} teardown unconfirmed; reconnect required",failure))
        }
    }

    suspend fun awaitClose(lease:Lease)=lease.completion.await()

    @Synchronized override fun close(){
        closed=true
        leases.values.forEach{it.completion.completeExceptionally(IOException("Audio session closed"))}
        leases.clear()
    }
}
