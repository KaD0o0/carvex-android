package io.github.iamr0s.carvex.sessioncore

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionTerminationTest {
    @Test fun normalStopRetainsResourcesAcrossFailureAndFinally()=runBlocking {
        var closes=0
        val termination=SessionTermination{closes++}
        assertTrue(termination.beginNormalStop())
        assertFalse(termination.beginNormalStop())
        termination.close();termination.close()
        assertEquals(0,closes)
        val waiting=async(start=CoroutineStart.UNDISPATCHED){termination.awaitClosed()}
        assertFalse(waiting.isCompleted)
        termination.finishNormalStop();waiting.await()
        termination.close();termination.finishNormalStop()
        assertEquals(1,closes)
    }

    @Test fun serviceDestructionCanInterruptNormalStop()=runBlocking {
        var closes=0
        val termination=SessionTermination{closes++}
        assertTrue(termination.beginNormalStop())
        termination.forceClose();termination.awaitClosed()
        termination.finishNormalStop()
        assertEquals(1,closes)
        assertFalse(termination.beginNormalStop())
    }

    @Test fun concurrentCloseDoesNotClaimCleanupHasCompleted()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val closes=AtomicInteger()
        val termination=SessionTermination{closes.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
        val owner=Thread{termination.close()}.apply{start()}
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertFalse(termination.beginNormalStop())
            termination.close()
            val waiting=async(start=CoroutineStart.UNDISPATCHED){termination.awaitClosed()}
            assertFalse(waiting.isCompleted)
            release.countDown();withTimeout(2000){waiting.await()}
            assertEquals(1,closes.get())
        }finally{release.countDown();owner.join(2000)}
    }

    @Test fun reentrantCloseDoesNotDeadlock()=runBlocking {
        lateinit var termination:SessionTermination
        termination=SessionTermination{termination.forceClose();termination.close()}
        termination.close();termination.awaitClosed()
    }

    @Test fun cleanupFailureStillReleasesTheCompletionBarrier()=runBlocking {
        val termination=SessionTermination{throw IllegalStateException("synthetic")}
        assertThrows(IllegalStateException::class.java){termination.close()}
        termination.awaitClosed()
    }
}
