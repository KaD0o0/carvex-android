package io.github.iamr0s.carvex.carplay.core
import java.io.*
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded sliding parser. Watchdog closes a stalled partial frame, but never an idle link.
 * The window is a plain byte array with a head/tail pair: an [ArrayDeque] of [Byte] would box
 * every single byte that arrives on the link. */
class IapFrameReader(private val input:InputStream,private val log:(String)->Unit={},
    private val failure:(Throwable)->Unit={},private val timeoutMs:Long=10000,
    private val detection:(()->Unit)?=null,
    private val packetVersion:()->Int={1}):AutoCloseable {
    private var buffer=ByteArray(10)
    private var head=0
    private var tail=0
    private val started=AtomicLong(0)
    private val closed=AtomicBoolean(false)
    private val timer=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"iap-frame-deadline").apply{isDaemon=true}}
    init {
        require(timeoutMs>0)
        timer.scheduleAtFixedRate({
            val at=started.get()
            if(at!=0L&&System.nanoTime()-at>=timeoutMs*1000000&&closed.compareAndSet(false,true)){
                val error=SocketTimeoutException("iAP partial frame deadline")
                runCatching{input.close()};failure(error)
            }
        },minOf(timeoutMs,100),minOf(timeoutMs,100),TimeUnit.MILLISECONDS)
    }
    private fun pending()=tail-head
    /** Compact towards the front, then grow, so [count] bytes fit contiguously at the tail. */
    private fun ensure(count:Int){
        if(head+count<=buffer.size)return
        if(head>0){System.arraycopy(buffer,head,buffer,0,pending());tail-=head;head=0}
        // count is validated against read(maxPacket) before payload allocation. No doubling
        // beyond the configured local RX bound, even when resynchronizing a corrupt candidate.
        if(count>buffer.size)buffer=buffer.copyOf(count)
    }
    private fun fill(count:Int){
        ensure(count)
        while(pending()<count){
            if(closed.get())throw SocketTimeoutException("iAP partial frame reader closed")
            val v=input.read();if(v<0)throw EOFException("iAP frame truncated")
            if(pending()==0)started.compareAndSet(0,System.nanoTime())
            buffer[tail++]=v.toByte()
        }
    }
    /** Drops one byte and re-checks from there; returns the updated budget usage. */
    private fun discard(discarded:Int):Int {
        // Corrupt candidates can contain certificate, signature or passphrase fragments. Count
        // rejected bytes without serializing any raw window into public diagnostic logs.
        if(discarded==0)log("iAP frame rejected: "+pending())
        head++
        require(discarded+1<=65535){"iAP resynchronization budget exceeded"}
        return discarded+1
    }
    fun read(maxPacket:Int=2048):IapWire.Frame {
        require(maxPacket in 9..65535)
        var discarded=0
        while(true){
            // The version is read once at each record boundary. A committed
            // version change therefore cannot reinterpret bytes already in a
            // partially buffered record.
            val version=packetVersion().also{require(it in 1..3)}
            val checksumBytes=if(version==2)2 else 1
            val headerLength=8+checksumBytes
            fill(2)
            if(detection!=null&&buffer[head]==(-1).toByte()&&buffer[head+1]==0x55.toByte()){
                fill(6)
                if((0 until 6).all{buffer[head+it]==IapWire.detection[it]}){
                    head+=6
                    if(head==tail){head=0;tail=0;started.set(0)}else started.set(System.nanoTime())
                    detection.invoke();continue
                }
            }
            if(buffer[head]!=(-1).toByte()||buffer[head+1]!=0x5a.toByte()){discarded=discard(discarded);continue}
            fill(headerLength)
            val length=IapWire.u16(buffer,head+2)
            val headerValid=IapWire.checksum(version,buffer.copyOfRange(head,head+8))
                .contentEquals(buffer.copyOfRange(head+8,head+headerLength))
            if(!headerValid||length !in headerLength..maxPacket){discarded=discard(discarded);continue}
            fill(length)
            val bodyLength=length-headerLength
            if(bodyLength!=0&&(bodyLength<=checksumBytes||!IapWire.checksum(version,
                    buffer.copyOfRange(head+headerLength,head+length-checksumBytes))
                    .contentEquals(buffer.copyOfRange(head+length-checksumBytes,head+length)))){
                discarded=discard(discarded);continue
            }
            val frame=IapWire.Frame(buffer[head+4].toInt() and 255,buffer[head+5].toInt() and 255,
                buffer[head+6].toInt() and 255,buffer[head+7].toInt() and 255,
                if(bodyLength==0)byteArrayOf()else buffer.copyOfRange(head+headerLength,head+length-checksumBytes))
            head+=length
            if(head==tail){head=0;tail=0;started.set(0)}else started.set(System.nanoTime())
            if(discarded>0)log("${"iAP recovered after discarding "}${discarded}${" invalid bytes"}")
            return frame
        }
    }
    override fun close(){closed.set(true);timer.shutdownNow()}
}
