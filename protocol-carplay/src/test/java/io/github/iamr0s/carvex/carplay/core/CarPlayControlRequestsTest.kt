package io.github.iamr0s.carvex.carplay.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.SocketTimeoutException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class CarPlayControlRequestsTest {
    private class Peer(event:(RtspMessage)->Pair<Int,ByteArray> = {200 to byteArrayOf()}):AutoCloseable {
        private val incoming=LinkedBlockingQueue<Int>()
        val sent=LinkedBlockingQueue<RtspMessage>()
        val aborted=AtomicInteger()
        val failed=LinkedBlockingQueue<Throwable>()
        private val input=object:InputStream(){override fun read():Int=incoming.take()}
        private val output=object:ByteArrayOutputStream(){
            override fun flush(){sent.put(RtspWire.read(ByteArrayInputStream(toByteArray())));reset()}
        }
        val client=RtspClient(input,output,event).apply{
            timeoutMs=2_000
            abortTransport={aborted.incrementAndGet();incoming.offer(-1)}
            onFailure={failed.put(it)}
            startReader()
        }
        fun next():RtspMessage=requireNotNull(sent.poll(3,TimeUnit.SECONDS)){"no outgoing message"}
        fun receive(first:String,headers:Map<String,String>,body:ByteArray=byteArrayOf()){
            val wire=ByteArrayOutputStream()
            RtspWire.write(wire,first,headers,body)
            wire.toByteArray().forEach{incoming.put(it.toInt() and 255)}
        }
        fun reply(request:RtspMessage,status:Int=200)=receive("RTSP/1.0 $status Test",mapOf("CSeq" to request.headers.getValue("cseq")))
        override fun close(){client.close();incoming.offer(-1)}
    }

    @Test fun iapWaitsForControlReplyWhileEventWorkerStillReplies(){
        val handled=CountDownLatch(1)
        Peer{handled.countDown();200 to byteArrayOf()}.use{peer->
            val pool=Executors.newSingleThreadExecutor()
            try{
                val bytes=byteArrayOf(0xff.toByte(),0x5a,0,10)
                val worker=pool.submit{CarPlayControlRequests.sendIap(peer.client,bytes);CarPlayControlRequests.sendIap(peer.client,byteArrayOf(9))}
                val first=peer.next()
                assertEquals("POST /command RTSP/1.0",first.first)
                val body=Plist.dictionary(first.body)
                assertEquals("iAPSendMessage",body["type"])
                assertArrayEquals(bytes,(body["params"] as Map<*,*>)["data"] as ByteArray)
                // An incoming command (e.g. an iAP ACK) must still be handled while
                // the phone's separate IO writer waits for its HTTP/RTSP response.
                peer.receive("POST /command RTSP/1.0",mapOf("CSeq" to "701"))
                assertTrue(handled.await(3,TimeUnit.SECONDS))
                val eventReply=peer.next()
                assertEquals(200,eventReply.status)
                assertEquals("701",eventReply.headers["cseq"])
                assertNull(peer.sent.poll(80,TimeUnit.MILLISECONDS))
                assertFalse(worker.isDone)
                peer.reply(first)
                val second=peer.next()
                peer.reply(second)
                worker.get(3,TimeUnit.SECONDS)
                assertTrue(peer.client.readerRunning)
                assertEquals(0,peer.client.pendingCount)
                assertEquals(0,peer.client.oneWayPendingCount)
            }finally{pool.shutdownNow()}
        }
    }

    @Test fun heartbeatHasAtMostOneRequestAndSilenceTerminatesTheLoop()=runBlocking {
        Peer().use{peer->
            peer.client.timeoutMs=180
            val result=async(Dispatchers.IO){runCatching{CarPlayControlRequests.keepAlive(peer.client,20)}.exceptionOrNull()}
            val first=peer.next()
            assertEquals("POST /feedback RTSP/1.0",first.first)
            assertTrue(first.body.isEmpty())
            val cause=withTimeout(3_000){result.await()}
            assertTrue(cause is SocketTimeoutException)
            assertNotNull(peer.failed.poll(3,TimeUnit.SECONDS))
            assertNull(peer.sent.poll())
            assertEquals(1,peer.aborted.get())
            assertFalse(peer.client.readerRunning)
        }
    }

    @Test fun matchingHeartbeatRepliesKeepTheConnectionUsableAndCancellationIsLocal()=runBlocking {
        Peer().use{peer->
            val loop=launch(Dispatchers.IO){CarPlayControlRequests.keepAlive(peer.client,20)}
            repeat(3){peer.reply(peer.next())}
            val pending=peer.next()
            assertEquals("POST /feedback RTSP/1.0",pending.first)
            loop.cancelAndJoin()
            assertTrue(peer.client.readerRunning)
            assertEquals(0,peer.client.pendingCount)
            assertEquals(0,peer.aborted.get())
            assertTrue(peer.failed.isEmpty())
        }
    }

    @Test fun heartbeatErrorStatusInvalidatesButIapCommandRefusalOnlyRejectsTheCommand(){
        for(heartbeat in listOf(true,false))Peer().use{peer->
            val pool=Executors.newSingleThreadExecutor()
            try{
                val worker=pool.submit<Throwable?>{runCatching{
                    if(heartbeat)CarPlayControlRequests.feedback(peer.client)
                    else CarPlayControlRequests.sendIap(peer.client,byteArrayOf(1))
                }.exceptionOrNull()}
                peer.reply(peer.next(),503)
                val cause=worker.get(3,TimeUnit.SECONDS)
                assertTrue(cause is RtspClient.StatusException)
                assertEquals(503,(cause as RtspClient.StatusException).status)
                assertEquals(!heartbeat,peer.client.readerRunning)
                assertEquals(if(heartbeat)1 else 0,peer.aborted.get())
            }finally{pool.shutdownNow()}
        }
    }

    @Test fun oneDeadlineWakesOtherRequestsAndTeardownDoesNotWriteAgain(){
        Peer().use{peer->
            val pool=Executors.newFixedThreadPool(2)
            try{
                // The first request keeps its original budget when timeoutMs changes.
                val long=pool.submit<Throwable?>{runCatching{peer.client.request("SETUP","/")}.exceptionOrNull()}
                peer.next()
                peer.client.timeoutMs=100
                val short=pool.submit<Throwable?>{runCatching{CarPlayControlRequests.feedback(peer.client)}.exceptionOrNull()}
                peer.next()
                val cause=short.get(3,TimeUnit.SECONDS)
                assertTrue(cause is SocketTimeoutException)
                assertSame(cause,long.get(3,TimeUnit.SECONDS))
                assertNotNull(peer.failed.poll(3,TimeUnit.SECONDS))
                assertEquals(0,peer.client.pendingCount)
                assertThrows(IOException::class.java){peer.client.request("TEARDOWN","/")}
                assertNull(peer.sent.poll())
                assertTrue(peer.failed.isEmpty())
            }finally{pool.shutdownNow()}
        }
    }

    @Test fun closeCancelsOldDeadlineAndDoesNotAffectNewConnection(){
        Peer().use{old->Peer().use{fresh->
            val pool=Executors.newFixedThreadPool(2)
            try{
                old.client.timeoutMs=100
                val waiting=pool.submit<Throwable?>{runCatching{CarPlayControlRequests.feedback(old.client)}.exceptionOrNull()}
                old.next();old.close()
                assertTrue(waiting.get(3,TimeUnit.SECONDS) is IOException)
                val next=pool.submit{CarPlayControlRequests.feedback(fresh.client)}
                fresh.reply(fresh.next());next.get(3,TimeUnit.SECONDS)
                assertNull(old.failed.poll(180,TimeUnit.MILLISECONDS))
                assertTrue(fresh.client.readerRunning)
                assertEquals(0,fresh.aborted.get())
                assertEquals(1,old.aborted.get())
            }finally{pool.shutdownNow()}
        }}
    }

    @Test fun deadlineAbortsABlockedWriteWithoutTakingItsLock(){
        val incoming=LinkedBlockingQueue<Int>()
        val started=CountDownLatch(1)
        val aborted=CountDownLatch(1)
        val input=object:InputStream(){override fun read():Int=incoming.take()}
        val output=object:OutputStream(){override fun write(value:Int){
            started.countDown()
            check(aborted.await(3,TimeUnit.SECONDS)){"deadline did not unblock socket write"}
            throw IOException("socket aborted")
        }}
        val client=RtspClient(input,output).apply{
            timeoutMs=150
            abortTransport={aborted.countDown();incoming.offer(-1)}
            startReader()
        }
        val pool=Executors.newSingleThreadExecutor()
        try{
            val worker=pool.submit<Throwable?>{runCatching{CarPlayControlRequests.feedback(client)}.exceptionOrNull()}
            assertTrue(started.await(3,TimeUnit.SECONDS))
            assertTrue(worker.get(3,TimeUnit.SECONDS) is SocketTimeoutException)
            assertFalse(client.readerRunning)
        }finally{client.close();pool.shutdownNow()}
    }
}
