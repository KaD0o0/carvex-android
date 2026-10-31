package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.*

class IapFrameReaderTest {
    @Test fun corruptCandidateResynchronizesToNextValidFrame(){
        val expected=IapWire.Frame(0x40,31,12,10,byteArrayOf(1,2,3))
        val good=IapWire.packet(expected)
        val bad=good.copyOf().apply{this[8]=(this[8].toInt() xor 1).toByte()}
        val logs=mutableListOf<String>()
        IapFrameReader(ByteArrayInputStream(bad+good),{logs.add(it)}).use{reader->
            val actual=reader.read()
            assertEquals(expected.seq,actual.seq)
            assertEquals(expected.session,actual.session)
            assertArrayEquals(expected.payload,actual.payload)
            assertTrue(logs.any{it.contains("recovered")})
        }
    }
    @Test(timeout=3000) fun idleIsAllowedButPartialHeaderExpires(){
        val input=BoundedByteInput()
        val failed=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        val reader=IapFrameReader(input,{}, {failed.countDown()},200)
        try{
            val result=executor.submit<IapWire.Frame>{reader.read()}
            assertFalse(failed.await(400,TimeUnit.MILLISECONDS))
            assertTrue(input.offer(byteArrayOf(-1,0x5a)))
            assertTrue(failed.await(1,TimeUnit.SECONDS))
            try{result.get(1,TimeUnit.SECONDS);fail("Partial frame must fail")}
            catch(expected:ExecutionException){assertNotNull(expected.cause)}
        }finally{reader.close();input.close();executor.shutdownNow()}
    }
    @Test fun corruptPayloadDiagnosticsDoNotDumpCredentialBytes(){
        val secret=ByteArray(40){listOf(0xde,0xad,0xbe,0xef)[it%4].toByte()}
        val bad=IapWire.packet(IapWire.Frame(0x40,1,0,10,secret)).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        val good=IapWire.packet(IapWire.Frame(0x40,2,0,0,byteArrayOf()))
        val logs=mutableListOf<String>()
        IapFrameReader(ByteArrayInputStream(bad+good),{logs.add(it)}).use{reader->
            assertEquals(2,reader.read().seq)
            assertTrue(logs.any{it.contains("recovered")})
            assertFalse(logs.any{it.contains("deadbeefdeadbeef")})
        }
    }
    @Test fun malformedDetectionResynchronizesAndValidMarkerHasItsOwnCallback(){
        val bad=byteArrayOf(-1,0x55,2,0,0xee.toByte(),0x11)
        val frame=IapWire.packet(IapWire.Frame(0x80,7,0,0,byteArrayOf(1)))
        var detections=0
        IapFrameReader(ByteArrayInputStream(bad+IapWire.detection+frame),detection={detections++}).use{reader->
            assertEquals(7,reader.read().seq)
            assertEquals(1,detections)
        }
    }
    @Test fun nineByteAckIsValidAtTheMinimumReaderLimit(){
        val bytes=IapWire.packet(IapWire.Frame(0x40,255,0,0,byteArrayOf()))
        IapFrameReader(ByteArrayInputStream(bytes)).use{reader->assertEquals(255,reader.read(9).seq)}
    }
}
