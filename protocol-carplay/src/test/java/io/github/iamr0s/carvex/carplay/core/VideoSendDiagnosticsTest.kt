package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class VideoSendDiagnosticsTest {
    @Test fun normalFramesDoNotLogOrCaptureStacks(){
        var now=0L;var captures=0
        val lines=mutableListOf<String>()
        val diagnostic=VideoSendDiagnostics(lines::add,{now},{captures++;emptyList()})
        val result=diagnostic.trace("frame"){ticket->
            for(phase in VideoSendDiagnostics.Phase.entries){now+=1_000_000;diagnostic.phase(ticket,phase);diagnostic.poll()}
            42
        }
        assertEquals(42,result);assertTrue(lines.isEmpty());assertEquals(0,captures)
        assertTrue(diagnostic.snapshot().operations.isEmpty())
    }

    @Test fun phasesExposeTotalAndCurrentPhaseAge(){
        var now=10L
        val diagnostic=VideoSendDiagnostics({}, {now})
        val ticket=diagnostic.begin("frame")
        assertEquals(VideoSendDiagnostics.Phase.LOCK_WAIT,diagnostic.snapshot().operations.single().phase)
        assertNull(diagnostic.snapshot().owner)
        now=1_000_000_010L;diagnostic.phase(ticket,VideoSendDiagnostics.Phase.ENCRYPT)
        now=1_250_000_010L
        val operation=diagnostic.snapshot().owner!!
        assertEquals("frame",operation.kind);assertEquals(1_250_000_000L,operation.elapsedNanos)
        assertEquals(250_000_000L,operation.phaseElapsedNanos)
        diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
        now+=1_000_000
        assertEquals(VideoSendDiagnostics.Phase.SOCKET_WRITE,diagnostic.snapshot().owner!!.phase)
        diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_FLUSH)
        assertEquals(VideoSendDiagnostics.Phase.SOCKET_FLUSH,diagnostic.snapshot().owner!!.phase)
        diagnostic.complete(ticket)
        assertNull(diagnostic.snapshot().owner)
    }

    @Test fun pendingLogsAreGloballyThrottledAndStacksStopAfterTwoSamples(){
        var now=0L;var captures=0;var extraQueries=0
        val lines=mutableListOf<String>()
        val diagnostic=VideoSendDiagnostics(lines::add,{now},{captures++;listOf("frame")},{extraQueries++;emptyList()})
        fun pendingCount()=lines.count{it.startsWith("Video send pending")}
        val ticket=diagnostic.begin("frame")
        diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
        now=1_999_999_999;diagnostic.poll();assertTrue(lines.isEmpty())
        now=2_000_000_000;diagnostic.poll();assertEquals(1,pendingCount())
        now=6_999_999_999;diagnostic.poll();assertEquals(1,pendingCount())
        now=7_000_000_000;diagnostic.poll();assertEquals(2,pendingCount())
        now=12_000_000_000;diagnostic.poll();assertEquals(3,pendingCount())
        assertEquals(2,captures);assertEquals(2,extraQueries)
        assertTrue(lines.last().contains("stacks=not-sampled"))
        diagnostic.complete(ticket)
        assertTrue(lines.last().contains("outcome=recovered"))
        now+=10_000_000_000;diagnostic.poll();assertEquals(6,lines.size)
    }

    @Test fun stackSamplingIsBoundedAndDeepFramesSurvivePerLineLogTruncation(){
        var now=0L
        val lines=mutableListOf<String>();val captured=mutableListOf<Thread>()
        val extra=(0..4).map{Thread("control-$it")}
        val diagnostic=VideoSendDiagnostics(lines::add,{now},{worker->
            captured+=worker;List(20){"depth-$it\n"+"x".repeat(500)}
        },{extra})
        val ticket=diagnostic.begin("frame\nunsafe")
        diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
        now=2_000_000_000;diagnostic.poll()
        assertEquals(3,captured.size)
        assertEquals(listOf(Thread.currentThread(),extra[0],extra[1]),captured)
        assertEquals(25,lines.size)
        assertTrue(lines.all{it.length<=450&&!it.contains('\n')})
        assertEquals(3,lines.count{it.contains("depth-7")})
        assertFalse(lines.any{it.contains("depth-8")||it.contains("x".repeat(181))})
        assertTrue(lines.last().contains("thread=control-1"))
        assertTrue(lines.last().contains("depth=8/8 depth-7"))
        diagnostic.complete(ticket)
    }

    @Test fun exceptionalCompletionClearsStateAndPreservesOriginalError(){
        var now=0L
        val lines=mutableListOf<String>()
        val diagnostic=VideoSendDiagnostics(lines::add,{now})
        val failure=IOException("expected")
        val actual=assertThrows(IOException::class.java){diagnostic.trace("frame"){ticket->
            now=500_000_000;diagnostic.phase(ticket,VideoSendDiagnostics.Phase.ENCRYPT)
            now=1_000_000_000;diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
            now=3_000_000_000;throw failure
        }}
        assertSame(failure,actual);assertTrue(diagnostic.snapshot().operations.isEmpty())
        assertEquals(1,lines.size);assertTrue(lines.single().contains("outcome=IOException"))
        assertTrue(lines.single().contains("lock-wait:500,encrypt:500,socket-write:2000,socket-flush:0"))
    }

    @Test fun diagnosticFailuresDoNotChangeVideoResultOrFailure(){
        var now=0L
        val diagnostic=VideoSendDiagnostics({throw IllegalStateException("logger")},{now},
            {throw SecurityException("stack")},{throw SecurityException("extra")})
        assertEquals(42,diagnostic.trace("frame"){ticket->
            diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
            now+=3_000_000_000;diagnostic.poll();42
        })
        assertTrue(diagnostic.snapshot().operations.isEmpty())
    }

    @Test fun completedTicketCannotChangeOrClearAnotherOperation(){
        var now=0L
        val lines=mutableListOf<String>()
        val diagnostic=VideoSendDiagnostics(lines::add,{now})
        val old=diagnostic.begin("frame")
        now=3_000_000_000;diagnostic.complete(old)
        val next=diagnostic.begin("keepalive")
        diagnostic.phase(next,VideoSendDiagnostics.Phase.SOCKET_FLUSH)
        diagnostic.phase(old,VideoSendDiagnostics.Phase.ENCRYPT);diagnostic.complete(old)
        assertEquals("keepalive",diagnostic.snapshot().owner!!.kind)
        assertEquals(1,diagnostic.snapshot().operations.size);assertEquals(1,lines.size)
        diagnostic.complete(next)
    }

    @Test(timeout=6000) fun heartbeatCanSeeWaitingFrameAndKeepaliveOwnerWithoutVideoMonitor(){
        val now=AtomicLong()
        val lines=mutableListOf<String>()
        val diagnostic=VideoSendDiagnostics({synchronized(lines){lines.add(it)}},now::get,{listOf("test-frame")})
        val videoLock=Any()
        val ownerReady=CountDownLatch(1);val waiterReady=CountDownLatch(1);val release=CountDownLatch(1)
        val owner=thread(name="test-video-keepalive"){
            diagnostic.trace("keepalive"){ticket->synchronized(videoLock){
                diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)
                ownerReady.countDown();release.await(4,TimeUnit.SECONDS)
            }}
        }
        var waiter:Thread?=null
        try{
            assertTrue(ownerReady.await(2,TimeUnit.SECONDS))
            waiter=thread(name="test-video-frame"){
                diagnostic.trace("frame"){ticket->
                    waiterReady.countDown()
                    synchronized(videoLock){diagnostic.phase(ticket,VideoSendDiagnostics.Phase.SOCKET_WRITE)}
                }
            }
            assertTrue(waiterReady.await(2,TimeUnit.SECONDS))
            now.set(2_000_000_000)
            val snapshot=diagnostic.snapshot()
            assertEquals("keepalive",snapshot.owner!!.kind)
            assertEquals(VideoSendDiagnostics.Phase.LOCK_WAIT,snapshot.operations.single{it.kind=="frame"}.phase)
            diagnostic.poll()
            assertTrue(lines.first().contains("owner=keepalive@test-video-keepalive"))
            assertTrue(lines.first().contains("phase=lock-wait"))
        }finally{release.countDown();owner.join(1500);waiter?.join(1500)}
        assertFalse(owner.isAlive);assertFalse(waiter?.isAlive?:false)
        assertTrue(diagnostic.snapshot().operations.isEmpty())
    }
}
