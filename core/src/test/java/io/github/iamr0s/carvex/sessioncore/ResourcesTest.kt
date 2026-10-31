package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResourcesTest {
    @Test fun slowIndividualReleaseDoesNotHoldTheRegistryAndBlockSocketCleanup(){
        val entered=CountDownLatch(1)
        val finish=CountDownLatch(1)
        val socketClosed=CountDownLatch(1)
        val resources=Resources()
        val slow=resources.own(AutoCloseable{entered.countDown();check(finish.await(5,TimeUnit.SECONDS))})
        resources.own(AutoCloseable{socketClosed.countDown()})
        val releasing=Thread{resources.release(slow)}.apply{isDaemon=true;start()}
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val stopping=Thread{resources.close()}.apply{isDaemon=true;start()}
            assertTrue("socket shutdown must not wait for a codec or Binder close",socketClosed.await(2,TimeUnit.SECONDS))
            stopping.join(2000)
            assertFalse(stopping.isAlive)
        }finally{finish.countDown();releasing.join(2000);resources.close()}
    }

    @Test fun closeTracesIdentifyAnIncompleteReleaseAndDoNotChangeReverseCleanup(){
        val order=mutableListOf<Int>()
        val traces=mutableListOf<Resources.CloseTrace>()
        val resources=Resources{traces+=it;throw IllegalStateException("logging failure")}
        resources.own(AutoCloseable{order+=1})
        resources.own(AutoCloseable{order+=2;throw java.io.IOException("sensitive message")})
        resources.close();resources.close()
        assertEquals(listOf(2,1),order)
        assertEquals(4,traces.size)
        assertNull(traces[0].elapsedMs)
        assertEquals("IOException",traces[1].error)
        assertNotNull(traces[1].elapsedMs)
        assertNull(traces[2].elapsedMs)
        assertNull(traces[3].error)
        assertFalse(traces.toString().contains("sensitive message"))
    }
}
