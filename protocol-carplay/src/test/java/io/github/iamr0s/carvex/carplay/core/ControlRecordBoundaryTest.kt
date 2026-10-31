package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ControlRecordBoundaryTest {
    private val key=ByteArray(32){it.toByte()}

    @Test fun fragmentedRecordsReassembleOneContinuousPlaintextStream(){
        val plaintext=ByteArray(4097){(it*17).toByte()}
        val wire=ByteArrayOutputStream().also{RecordOutput(it,key).write(plaintext)}.toByteArray()
        val fragmented=object:ByteArrayInputStream(wire){override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,1))}
        assertArrayEquals(plaintext,IapWire.exact(RecordInput(fragmented,key),plaintext.size))
    }

    @Test fun failedTagClosesAndPoisonsTheReader(){
        val wire=ByteArrayOutputStream().also{RecordOutput(it,key).write(byteArrayOf(42))}.toByteArray()
        wire[wire.lastIndex]=(wire.last().toInt() xor 1).toByte()
        var closed=false
        val source=object:ByteArrayInputStream(wire){override fun close(){closed=true;super.close()}}
        val records=RecordInput(source,key)
        assertThrows(Exception::class.java){records.read()}
        assertTrue(closed)
        assertThrows(IllegalStateException::class.java){records.read()}
    }

    @Test fun partialWriteFailureCannotReuseTheRecordEpoch(){
        var writes=0
        var closed=false
        val sink=object:OutputStream(){
            override fun write(value:Int){writes++;throw IOException("synthetic partial write")}
            override fun close(){closed=true}
        }
        val records=RecordOutput(sink,key)
        assertThrows(IOException::class.java){records.write(byteArrayOf(1,2))}
        assertTrue(closed)
        assertThrows(IllegalStateException::class.java){records.write(byteArrayOf(3))}
        assertEquals(1,writes)
    }

    @Test fun controlEncryptionCannotBeInstalledTwice(){
        val client=RtspClient(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream())
        client.encrypted(key)
        assertThrows(IllegalStateException::class.java){client.encrypted(key)}
    }

    @Test fun stateAndErrorAreSingleByteNonFragmentedFields(){
        listOf(byteArrayOf(6,0),byteArrayOf(7,2,1,2),byteArrayOf(6,1,2,6,1,2)).forEach{wire->
            assertThrows(IllegalArgumentException::class.java){Tlv8.decode(wire)}
        }
        assertArrayEquals(byteArrayOf(2),Tlv8.decode(byteArrayOf(6,1,2))[6])
    }
}
