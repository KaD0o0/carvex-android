package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ControlReadDiagnosticsTest {
    private val key=ByteArray(32){7}
    private val message="RTSP/1.0 200 OK\r\nContent-Length: 6\r\n\r\nsecret".toByteArray()
    private fun encrypted(bytes:ByteArray):ByteArray=ByteArrayOutputStream().also{RecordOutput(it,key).write(bytes)}.toByteArray()
    private fun input(source:InputStream,progress:ControlReadDiagnostics)=RecordInput(progress.raw(source),key).also{it.diagnostics=progress}

    /** Each offered chunk is one underlying read; an empty chunk is EOF.
     * readStarted makes partial/blocking observations deterministic without sleeps. */
    private class ChunkInput:InputStream(){
        val chunks=LinkedBlockingQueue<ByteArray>()
        val readStarted=LinkedBlockingQueue<Int>()
        private var current=byteArrayOf()
        private var offset=0
        private var eof=false
        override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255}
        override fun read(b:ByteArray,off:Int,len:Int):Int {
            if(len==0)return 0
            readStarted.put(len)
            if(eof)return -1
            if(offset==current.size){
                current=chunks.take();offset=0
                if(current.isEmpty()){eof=true;return -1}
            }
            val n=minOf(len,current.size-offset)
            current.copyInto(b,off,offset,offset+n);offset+=n
            return n
        }
        fun waiting(expected:Int){assertEquals(expected,readStarted.poll(3,TimeUnit.SECONDS))}
        override fun close(){chunks.offer(byteArrayOf())}
    }

    @Test(timeout=6000) fun partialRecordShowsHeaderAndCiphertextRemainingWithoutExtraReads(){
        val source=ChunkInput();val progress=ControlReadDiagnostics();val wire=encrypted(message)
        val pool=Executors.newSingleThreadExecutor()
        try{
            val worker=pool.submit<RtspMessage>{RtspWire.read(input(source,progress),progress)}
            source.waiting(2)
            assertTrue(progress.snapshot().contains("recordPhase=header recordRemaining=2"))
            assertTrue(progress.snapshot().contains("rawBytes=0 rawAgeMs=none"))
            source.chunks.put(wire.copyOfRange(0,1))
            source.waiting(1)
            assertTrue(progress.snapshot().contains("recordPhase=header recordRemaining=1"))
            source.chunks.put(wire.copyOfRange(1,7))
            source.waiting(wire.size-2)
            source.waiting(wire.size-7)
            val partial=progress.snapshot()
            assertTrue(partial,partial.contains("rawBytes=7"))
            assertTrue(partial,partial.contains("recordPhase=ciphertext recordRemaining="+(wire.size-7)))
            assertTrue(partial,partial.contains("rtspPhase=header"))
            source.chunks.put(wire.copyOfRange(7,wire.size))
            assertEquals(200,worker.get(3,TimeUnit.SECONDS).status)
            assertTrue(progress.snapshot().contains("recordPhase=buffered recordRemaining=0"))
            assertTrue(progress.snapshot().contains("rtspPhase=complete"))
            assertTrue(source.readStarted.isEmpty())
        }finally{source.close();pool.shutdownNow()}
    }

    @Test(timeout=6000) fun bodyWaitingForNextEncryptedRecordIsDistinctFromIncompleteHeader(){
        val source=ChunkInput();val progress=ControlReadDiagnostics()
        val output=ByteArrayOutputStream();val records=RecordOutput(output,key)
        records.write(message.copyOfRange(0,message.size-6));val split=output.size()
        records.write(message.copyOfRange(message.size-6,message.size));val wire=output.toByteArray()
        val pool=Executors.newSingleThreadExecutor()
        try{
            source.chunks.put(wire.copyOfRange(0,split))
            val worker=pool.submit<RtspMessage>{RtspWire.read(input(source,progress),progress)}
            source.waiting(2);source.waiting(split-2);source.waiting(2)
            val waiting=progress.snapshot()
            assertTrue(waiting,waiting.contains("rtspPhase=body bodyExpected=6"))
            assertTrue(waiting,waiting.contains("recordPhase=header recordRemaining=2"))
            source.chunks.put(wire.copyOfRange(split,wire.size))
            assertArrayEquals("secret".toByteArray(),worker.get(3,TimeUnit.SECONDS).body)
            assertFalse(progress.snapshot().contains("secret"))
        }finally{source.close();pool.shutdownNow()}
    }

    @Test fun eofPreservesIncompleteRecordProgressAndException(){
        val wire=encrypted(message);val progress=ControlReadDiagnostics()
        val failure=assertThrows(EOFException::class.java){
            RtspWire.read(input(ByteArrayInputStream(wire.copyOf(7)),progress),progress)
        }
        assertTrue(failure.message!!.contains("Truncated"))
        val snapshot=progress.snapshot()
        assertTrue(snapshot,snapshot.contains("rawState=eof rawBytes=7"))
        assertTrue(snapshot,snapshot.contains("recordPhase=ciphertext recordRemaining="+(wire.size-7)))
        assertTrue(snapshot,snapshot.contains("rtspPhase=header"))
        assertFalse(snapshot.contains("secret"))
    }

    @Test fun completeSingleRecordDoesNotReadBeyondRequestedPlaintext(){
        var calls=0
        val wire=encrypted(message)
        val source=object:ByteArrayInputStream(wire){
            override fun read(b:ByteArray,off:Int,len:Int):Int {calls++;return super.read(b,off,len)}
        }
        val progress=ControlReadDiagnostics()
        val decoded=RtspWire.read(input(source,progress),progress)
        assertArrayEquals("secret".toByteArray(),decoded.body)
        assertEquals(2,calls)
        assertTrue(progress.snapshot().contains("rawBytes="+wire.size))
    }

    @Test fun zeroLengthReadRetainsDelegateBehaviorAndDoesNotInventArrival(){
        val progress=ControlReadDiagnostics();val raw=progress.raw(ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(0,raw.read(ByteArray(0),0,0))
        assertTrue(progress.snapshot().contains("rawBytes=0 rawAgeMs=none"))
        assertEquals(1,raw.read())
        assertEquals(-1,raw.read())
        assertTrue(progress.snapshot().contains("rawState=eof rawBytes=1"))
    }

    @Test(timeout=6000) fun clientExposesOnlyOwnedReaderAndPreservesFailureStage(){
        val source=ChunkInput();val target=RtspClient(source,ByteArrayOutputStream())
        val failed=CountDownLatch(1)
        target.onFailure={assertTrue(it is EOFException);failed.countDown()}
        assertNull(target.diagnosticReaderThread())
        target.encrypted(ByteArray(32){4})
        try{
            target.startReader();source.waiting(2)
            assertEquals("rtsp-reader",target.diagnosticReaderThread()?.name)
            assertTrue(target.readDiagnostics().contains("readerPhase=read"))
            assertTrue(target.readDiagnostics().contains("recordPhase=header recordRemaining=2"))
            source.close()
            assertTrue(failed.await(3,TimeUnit.SECONDS))
            assertTrue(target.readDiagnostics().contains("readerPhase=failed"))
            assertTrue(target.readDiagnostics().contains("rawState=eof"))
        }finally{target.close();source.close()}
    }
}
