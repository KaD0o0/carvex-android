package io.github.iamr0s.carvex.carplay.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

/** 24A446 ordinary (non-RCS) commands use TransportControlStream and
 * SendMessageCreatingReply, even when the caller discards the reply body.
 * RTSP completion is NOT the separate iAP2 link ACK. */
internal object CarPlayControlRequests {
    fun sendIap(client:RtspClient,bytes:ByteArray){
        val body=Plist.encode(mapOf("type" to "iAPSendMessage","params" to mapOf("data" to bytes)))
        val reply=client.request("POST","/command",body)
        if(reply.status !in 200..299)throw RtspClient.StatusException(reply.status,"POST","/command")
    }

    fun feedback(client:RtspClient){
        val reply=client.request("POST","/feedback")
        if(reply.status !in 200..299){
            val failure=RtspClient.StatusException(reply.status,"POST","/feedback")
            client.invalidate(failure)
            throw failure
        }
    }

    /** One heartbeat in flight. Wait off the reader/event workers; skip missed
     * timer ticks instead of accumulating requests behind a slow receiver. */
    suspend fun keepAlive(client:RtspClient,periodMs:Long=2_017){
        require(periodMs>0)
        val period=periodMs*1_000_000L
        var due=System.nanoTime()+period
        while(currentCoroutineContext().isActive){
            val waitNs=due-System.nanoTime()
            if(waitNs>0)delay((waitNs+999_999)/1_000_000)
            runInterruptible(Dispatchers.IO){feedback(client)}
            due+=period
            val now=System.nanoTime()
            if(due<=now)due+=((now-due)/period+1)*period
        }
    }
}
