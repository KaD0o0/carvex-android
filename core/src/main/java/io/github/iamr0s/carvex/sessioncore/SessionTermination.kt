package io.github.iamr0s.carvex.sessioncore

import kotlinx.coroutines.CompletableDeferred

/** One attempt's cleanup owner. Normal stop temporarily retains the control
 * transport; failure/finally may request cleanup but cannot race its TEARDOWN.
 * No callbacks or waits run under the state lock. Service destruction can force
 * cleanup even while a normal stop is waiting for a peer. */
class SessionTermination(private val cleanup:()->Unit):AutoCloseable {
    private val lock=Any()
    private var retained=false
    private var closing=false
    private val completion=CompletableDeferred<Unit>()

    fun beginNormalStop():Boolean=synchronized(lock){
        if(retained||closing)false else {retained=true;true}
    }

    override fun close()=close(force=false)
    fun finishNormalStop()=close(force=true)
    fun forceClose()=close(force=true)

    private fun close(force:Boolean){
        synchronized(lock){
            if(closing||retained&&!force)return
            closing=true
        }
        try{cleanup()}finally{completion.complete(Unit)}
    }

    /** Also waits for a concurrent cleanup owner, unlike a second Resources.close(). */
    suspend fun awaitClosed(){completion.await()}
}
