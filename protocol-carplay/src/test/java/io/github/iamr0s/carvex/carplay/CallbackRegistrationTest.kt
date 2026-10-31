package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CallbackRegistrationTest {
    @Test fun registeredResourceUnregistersOnce() {
        var installed=0;var removed=0
        val registration=CallbackRegistration({installed++},{removed++})
        registration.start();registration.close();registration.close()
        assertEquals(1,installed);assertEquals(1,removed)
    }
    @Test fun closeBeforeStartPreventsRegistration() {
        var installed=0;var removed=0
        val registration=CallbackRegistration({installed++},{removed++})
        registration.close()
        assertThrows(IllegalStateException::class.java){registration.start()}
        assertEquals(0,installed);assertEquals(0,removed)
    }
    @Test fun duplicateStartCannotLeakSecondRegistration() {
        var installed=0
        val registration=CallbackRegistration({installed++},{})
        registration.start();assertThrows(IllegalStateException::class.java){registration.start()}
        registration.close();assertEquals(1,installed)
    }
    @Test fun synchronousCancellationInsideRegisterRemovesAfterInstall() {
        val events=mutableListOf<String>();lateinit var registration:CallbackRegistration
        registration=CallbackRegistration({events+="register";registration.close();events+="installed"},{events+="remove"})
        registration.start();registration.close()
        assertEquals(listOf("register","installed","remove"),events)
    }
    @Test fun failedRegistrationAttemptsCleanupAndKeepsOriginalFailure() {
        var removed=0;val original=IOException("register failed")
        val registration=CallbackRegistration({throw original},{removed++;throw IOException("remove failed")})
        assertSame(original,assertThrows(IOException::class.java){registration.start()})
        registration.close();assertEquals(1,removed)
    }
    @Test(timeout=5000) fun concurrentCloseWaitsForInstallationThenRemovesExactlyOnce() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closing=CountDownLatch(1)
        val removed=AtomicInteger();val pool=Executors.newFixedThreadPool(2)
        val registration=CallbackRegistration({entered.countDown();check(release.await(2,TimeUnit.SECONDS))},{removed.incrementAndGet()})
        try{
            val start=pool.submit{registration.start()};assertTrue(entered.await(2,TimeUnit.SECONDS))
            val close=pool.submit{closing.countDown();registration.close()};assertTrue(closing.await(2,TimeUnit.SECONDS))
            assertEquals(0,removed.get());release.countDown()
            start.get(2,TimeUnit.SECONDS);close.get(2,TimeUnit.SECONDS)
            registration.close();assertEquals(1,removed.get())
        }finally{release.countDown();pool.shutdownNow()}
    }
}
