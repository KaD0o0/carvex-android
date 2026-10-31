package io.github.iamr0s.carvex.carplay.core
import java.nio.ByteBuffer

/** D2/D3 uses an artificial monotonic epoch; standard NTP uses a separately anchored wall clock. */
object CarPlayTiming {
    /** Sample wall time once so a clock adjustment cannot make later NTP replies jump. */
    class WallClock(
        anchorUnixMillis:Long=System.currentTimeMillis(),
        private val anchorMonotonicNs:Long=System.nanoTime()
    ) {
        private val anchorSeconds=Math.floorDiv(anchorUnixMillis,1000L)
        private val anchorFractionNs=Math.floorMod(anchorUnixMillis,1000L)*1_000_000L

        fun ntp(monotonicNs:Long):Long {
            val elapsedNs=Math.subtractExact(monotonicNs,anchorMonotonicNs)
            val fractionNs=anchorFractionNs+Math.floorMod(elapsedNs,1_000_000_000L)
            val seconds=anchorSeconds+Math.floorDiv(elapsedNs,1_000_000_000L)+
                Math.floorDiv(fractionNs,1_000_000_000L)+2208988800L
            val fraction=Math.floorMod(fractionNs,1_000_000_000L)*0x100000000L/1_000_000_000L
            return ((seconds and 0xffffffffL) shl 32) or fraction
        }
    }

    /** The artificial epoch used only by the confirmed 32-byte D2/D3 exchange. */
    fun ntp(monotonicNs:Long):Long {
        require(monotonicNs>=0)
        val seconds=monotonicNs/1_000_000_000L+2208988800L
        val fraction=(monotonicNs%1_000_000_000L)*0x100000000L/1_000_000_000L
        return ((seconds and 0xffffffffL) shl 32) or fraction
    }
    /** The caller must pass one exact datagram and validate its peer before replying. */
    fun reply(request:ByteArray,rxMonotonicNs:Long,txMonotonicNs:Long,wallClock:WallClock?=null):ByteArray? {
        if(request.size==48) {
            val first=request[0].toInt() and 255
            val version=(first ushr 3) and 7
            if((first and 7)!=3||version !in 3..4||wallClock==null)return null
            return ByteArray(48).also{b->
                b[0]=((version shl 3) or 4).toByte();b[1]=2;b[3]=0xfa.toByte()
                request.copyInto(b,24,40,48)
                ByteBuffer.wrap(b).putLong(32,wallClock.ntp(rxMonotonicNs)).putLong(40,wallClock.ntp(txMonotonicNs))
            }
        }
        if(request.size!=32||(request[0].toInt() and 0xc0)!=0x80||(request[1].toInt() and 255)!=0xd2)return null
        val tx=ntp(txMonotonicNs)
        return ByteArray(32).also{b->
            b[0]=0x80.toByte();b[1]=0xd3.toByte();b[3]=7
            ByteBuffer.wrap(b).putInt(4,(tx ushr 32).toInt()).putLong(16,ntp(rxMonotonicNs)).putLong(24,tx)
            request.copyInto(b,8,24,32)
        }
    }
}

/** SIRI_EVENT_COMMAND_V1: only the integer branches 2/3 have a confirmed adapter mapping. */
object SiriCommand {
    fun action(command:Map<String,Any?>):Int? {
        if(command["type"]!="requestSiri")return null
        val raw=(command["params"] as? Map<*,*>)?.get("siriAction")
        val value=when(raw){is Int->raw.toLong();is Long->raw;else->return null}
        return value.takeIf{it==2L||it==3L}?.toInt()
    }
}
