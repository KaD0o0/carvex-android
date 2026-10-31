package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*
import kotlinx.coroutines.*
import java.net.*
import io.github.iamr0s.carvex.carplay.core.AudioRetransmitProtocol
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** AirPlay RTCP retransmit request/response (PT 213/214) for one audio stream. */
class AudioRtpRetransmit(private val socket:DatagramSocket,private val peer:InetAddress,private val log:(String)->Unit):AutoCloseable {
    private val closed=AtomicBoolean(false);private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val history=LinkedHashMap<Int,ByteArray>(512,0.75f,false)
    private val lock=Any();private var requests=0L;private var resent=0L;private var missed=0L;private var ignored=0L
    init{scope.launch{listen()}}
    fun record(sequence:Int,packet:ByteArray){synchronized(lock){if(closed.get())return;history[sequence and 65535]=packet.copyOf();while(history.size>512)history.remove(history.entries.first().key)}}
    private suspend fun listen(){
        val buf=ByteArray(2048)
        try{while(currentCoroutineContext().isActive&&!closed.get()){
            val dat=DatagramPacket(buf,buf.size);socket.receive(dat)
            if(dat.address!=peer||dat.length<8)continue
            val request=AudioRetransmitProtocol.request(buf.copyOfRange(dat.offset,dat.offset+dat.length))
            if(request==null){
                ignored++
                if(ignored==1L)log("Audio RTCP unhandled first type="+(buf[dat.offset+1].toInt() and 255)+" bytes="+dat.length)
                continue
            }
            // Bounded work per request is a local budget, not a protocol capability limit.
            val start=request.start;val count=minOf(request.count,512)
            requests++
            if(requests==1L)log("Audio first retransmit request start="+start+" count="+request.count+" cached="+synchronized(lock){history.size})
            for(offset in 0 until count){
                val seq=(start+offset) and 65535;val packet=synchronized(lock){history[seq]?.copyOf()}
                if(packet==null){missed++;sendFailure(dat.socketAddress,seq);continue}
                if(packet.size>1440){missed++;continue}
                val response=AudioRetransmitProtocol.response(packet)
                socket.send(DatagramPacket(response,response.size,dat.socketAddress));resent++
            }
            if(requests%20==0L)log("${"Audio retransmit requests="}${requests}${" resent="}${resent}${" missed="}${missed}")
        }}catch(t:CancellationException){throw t}catch(t:Throwable){if(currentCoroutineContext().isActive)log("Audio retransmit listener stopped: "+t.javaClass.simpleName+" "+(t.message?:""))}
    }
    private fun sendFailure(address:SocketAddress,seq:Int){
        val response=AudioRetransmitProtocol.failure(seq)
        socket.send(DatagramPacket(response,response.size,address))
    }
    override fun close(){if(!closed.compareAndSet(false,true))return;socket.close();scope.cancel();synchronized(lock){history.clear()}}
}
