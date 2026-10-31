package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProtocolCompatibilityTest {
    private val sps=byteArrayOf(0x67,0x42,0,0x1e)
    private val pps=byteArrayOf(0x68,1)
    private fun annex(vararg units:ByteArray)=units.fold(byteArrayOf()){a,b->a+byteArrayOf(0,0,0,1)+b}
    @Test fun combinedAndSplitCsdProduceSameConfig(){
        val combined=AvcConfiguration();combined.ingest(annex(sps,pps))
        val split=AvcConfiguration();split.ingest(sps);assertNull(split.takeChanged());split.ingest(pps)
        assertArrayEquals(combined.takeChanged(),split.takeChanged())
        combined.ingest(annex(sps,pps));assertNull(combined.takeChanged())
        combined.ingest(annex(byteArrayOf(0x67,0x42,0,0x1f)))
        assertNotNull(combined.takeChanged())
    }
    @Test fun lengthPrefixedPayloadWithInternalStartCodeIsNotMisclassified(){
        val payload=byteArrayOf(0x65,0,0,1,0x55)
        val frame=byteArrayOf(0,0,0,5)+payload
        assertArrayEquals(payload,VideoWire.splitNal(frame).single())
        assertArrayEquals(frame,VideoWire.annexBToLengths(frame))
    }
    @Test fun commandBodyFailureDoesNotPoisonNextCommand(){
        assertEquals(400,CommandBoundary.handle(byteArrayOf(1,2,3)){200 to byteArrayOf()}.first)
        val body=Plist.encode(mapOf("type" to "hidSendReport"))
        assertEquals(400,CommandBoundary.handle(body){throw IllegalArgumentException("bad report")}.first)
        assertEquals(200,CommandBoundary.handle(body){200 to byteArrayOf()}.first)
    }
    @Test fun transportFailureIsNotConvertedToCommandRejection(){
        val body=Plist.encode(mapOf("type" to "hidSendReport"))
        assertThrows(IOException::class.java){CommandBoundary.handle(body){throw IOException("transport")}}
    }
    @Test fun framePackingPreservesNalOrderAndRejectsEmptyNal(){
        val first=byteArrayOf(0x65,1,2);val last=byteArrayOf(0x41,3)
        val packed=VideoWire.lengthPrefixed(listOf(first,last))
        val decoded=VideoWire.splitNal(packed)
        assertArrayEquals(first,decoded[0]);assertArrayEquals(last,decoded[1])
        assertThrows(IllegalArgumentException::class.java){VideoWire.lengthPrefixed(listOf(byteArrayOf()))}
        val config=AvcConfiguration();config.ingestUnits(listOf(sps,pps));assertNotNull(config.takeChanged())
        repeat(100){config.ingestUnits(listOf(first,last));assertNull(config.takeChanged())}
    }
}
