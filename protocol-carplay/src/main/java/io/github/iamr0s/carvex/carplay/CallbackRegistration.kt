package io.github.iamr0s.carvex.carplay

/** Registers only while owned and serializes teardown against registration.
 * Handles both reentrant cancellation from register() and ordinary close races. */
internal class CallbackRegistration(private val register:()->Unit,private val unregister:()->Unit):AutoCloseable {
    private var closed=false
    private var attempted=false
    private var installed=false
    @Synchronized fun start(){
        check(!closed){"Registration already closed"}
        check(!attempted){"Registration already attempted"}
        attempted=true
        try{register();installed=true}
        catch(t:Throwable){closed=true;runCatching(unregister);throw t}
        if(closed)remove()
    }
    private fun remove(){if(installed){installed=false;runCatching(unregister)}}
    @Synchronized override fun close(){closed=true;remove()}
}
