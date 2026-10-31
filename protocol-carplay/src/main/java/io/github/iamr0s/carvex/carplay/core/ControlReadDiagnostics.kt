package io.github.iamr0s.carvex.carplay.core

import java.io.InputStream

/** Single-reader observations only: no extra reads, available() calls, payloads or keys.
 * A snapshot is approximate across fields and must not be used for protocol decisions. */
internal class ControlReadDiagnostics {
    @Volatile private var rawBytes=0L
    @Volatile private var lastRawNanos=0L
    @Volatile private var rawState="idle"
    @Volatile private var recordPhase="plain"
    @Volatile private var recordEnd=0L
    @Volatile private var rtspPhase="idle"
    @Volatile private var bodyExpected=0
    @Volatile private var readerPhase="not-started"

    fun raw(input:InputStream):InputStream=object:InputStream(){
        override fun read():Int=observe{input.read()}.also{if(it>=0)arrived(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=observe{input.read(b,off,len)}.also{if(it>0)arrived(it)}
        private inline fun observe(block:()->Int):Int {
            rawState="read"
            try{return block().also{rawState=if(it<0)"eof" else "returned"}}
            catch(t:Throwable){rawState=if(t is java.io.EOFException)"eof" else "failed";throw t}
        }
        private fun arrived(count:Int){rawBytes+=count;lastRawNanos=System.nanoTime()}
        override fun close()=input.close()
    }
    fun record(phase:String,remaining:Int=0){recordEnd=rawBytes+remaining;recordPhase=phase}
    fun rtsp(phase:String,expected:Int=0){bodyExpected=expected;rtspPhase=phase}
    fun reader(phase:String){readerPhase=phase}
    fun snapshot(nowNanos:Long=System.nanoTime()):String {
        val last=lastRawNanos
        val age=if(last==0L)"none" else ((nowNanos-last).coerceAtLeast(0)/1_000_000).toString()
        return "readerPhase=$readerPhase rawState=$rawState rawBytes=$rawBytes rawAgeMs=$age"+
            " recordPhase=$recordPhase recordRemaining="+(recordEnd-rawBytes).coerceAtLeast(0)+
            " rtspPhase=$rtspPhase bodyExpected=$bodyExpected"
    }
}
