package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** Digest is terminal for this unsupported schema. Other statuses remain visible to the caller. */
class RtspClientTest {
    private enum class WriteMode { SYNC, ASYNC, ONE_WAY }

    /** Exercise the real client and writer, not a model header map. Async replies
     * become readable only after the complete request is flushed; no timing sleep. */
    private fun serializedRequest(mode:WriteMode,method:String,path:String,body:ByteArray=byteArrayOf(),
                                  extra:Map<String,String> = emptyMap(),written:ByteArrayOutputStream=ByteArrayOutputStream()):ByteArray {
        val replyOut=if(mode==WriteMode.ASYNC)java.io.PipedOutputStream() else null
        val input=if(replyOut!=null)java.io.PipedInputStream(replyOut) else
            ByteArrayInputStream(if(mode==WriteMode.SYNC)response("200 OK") else byteArrayOf())
        val output=object:java.io.OutputStream(){
            override fun write(value:Int){written.write(value)}
            override fun write(bytes:ByteArray,offset:Int,length:Int){written.write(bytes,offset,length)}
            override fun flush(){replyOut?.let{it.write(response("200 OK"));it.flush()}}
        }
        val target=RtspClient(input,output)
        try{
            if(mode==WriteMode.ASYNC)target.startReader()
            if(mode==WriteMode.ONE_WAY)target.send(method,path,body,extra=extra)
            else assertEquals(200,target.request(method,path,body,extra=extra).status)
            return written.toByteArray()
        }finally{target.close();replyOut?.close();input.close()}
    }

    private fun protocolHeaderLines(wire:ByteArray)=wire.toString(Charsets.US_ASCII)
        .substringBefore("\r\n\r\n").split("\r\n").filter{
            it.substringBefore(':').equals("X-Apple-ProtocolVersion",ignoreCase=true)}

    @Test fun controlVideoUpdateAndInfoSerializeOneProfileHeaderInEveryWriteMode(){
        val initial=Plist.encode(mapOf("updateSessionRequest" to false))
        val video=Plist.encode(mapOf("streams" to listOf(mapOf("type" to 110))))
        val update=Plist.encode(mapOf("updateSessionRequest" to true))
        val cases=listOf(
            Triple("SETUP","rtsp://192.0.2.1/123",initial),
            Triple("SETUP","rtsp://192.0.2.1/123",video),
            Triple("SETUP","rtsp://192.0.2.1/123",update),
            Triple("SETUP","/setup",initial),
            Triple("SETUP","/",video),
            Triple("GET","/info",byteArrayOf()))
        for(mode in WriteMode.values())for((method,path,body) in cases){
            val wire=serializedRequest(mode,method,path,body)
            assertEquals(listOf("X-Apple-ProtocolVersion: 1"),protocolHeaderLines(wire))
            val parsed=RtspWire.read(ByteArrayInputStream(wire))
            assertEquals("$method $path RTSP/1.0",parsed.first)
            assertEquals("1",parsed.headers["x-apple-protocolversion"])
            assertEquals("AirPlay/320.17",parsed.headers["user-agent"])
            assertEquals("1",parsed.headers["cseq"])
            assertArrayEquals(body,parsed.body)
        }
    }

    @Test fun unrelatedRequestsAndUnmappedInfoVariantsDoNotGainAProtocolHeader(){
        val cases=listOf("POST" to "/pair-setup","POST" to "/pair-verify",
            "POST" to "/auth-setup","POST" to "/command","POST" to "/feedback",
            "RECORD" to "rtsp://192.0.2.1/123","TEARDOWN" to "rtsp://192.0.2.1/123",
            "POST" to "/info","GET" to "/info?qualifier=displayCapabilities",
            "GET" to "/info/","GET" to "/information","GET" to "/INFO",
            "GET" to "rtsp://192.0.2.1/info")
        for(mode in WriteMode.values())for((method,path) in cases){
            val wire=serializedRequest(mode,method,path)
            assertTrue("$mode $method $path",protocolHeaderLines(wire).isEmpty())
            val parsed=RtspWire.read(ByteArrayInputStream(wire))
            assertEquals("$method $path RTSP/1.0",parsed.first)
            assertFalse(parsed.headers.containsKey("x-apple-protocolversion"))
        }
    }

    @Test fun matchingProtocolExtrasCoalesceCaseInsensitivelyWithoutChangingOtherExtras(){
        val extras=linkedMapOf("x-apple-protocolversion" to "1","X-Apple-ProtocolVersion" to "1",
            "X-APPLE-PROTOCOLVERSION" to "1","User-Agent" to "Explicit-Agent",
            "X-Test" to "first","x-test" to "second")
        for(mode in WriteMode.values())for((method,path) in listOf("SETUP" to "/","GET" to "/info")){
            val wire=serializedRequest(mode,method,path,extra=extras)
            assertEquals(listOf("X-Apple-ProtocolVersion: 1"),protocolHeaderLines(wire))
            val header=wire.toString(Charsets.US_ASCII).substringBefore("\r\n\r\n")
            assertTrue(header.contains("\r\nUser-Agent: Explicit-Agent"))
            // This fix must not expand into changing unrelated legacy extra-header semantics.
            assertTrue(header.contains("\r\nX-Test: first"))
            assertTrue(header.contains("\r\nx-test: second"))
        }
    }

    @Test fun conflictingProtocolExtrasFailBeforeWritingInEveryMode(){
        val extras=listOf(mapOf("X-Apple-ProtocolVersion" to "0"),
            mapOf("x-apple-protocolversion" to "2"),mapOf("X-APPLE-PROTOCOLVERSION" to "3"),
            mapOf("X-Apple-ProtocolVersion" to "1 "),mapOf("X-Apple-ProtocolVersion" to "1\r\nX-Test: value"),
            linkedMapOf("X-Apple-ProtocolVersion" to "1","x-apple-protocolversion" to "2"),
            linkedMapOf("X-Apple-ProtocolVersion" to "2","x-apple-protocolversion" to "1"))
        for(mode in WriteMode.values())for(extra in extras){
            val written=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java){
                serializedRequest(mode,"SETUP","/",extra=extra,written=written)}
            assertEquals("$mode $extra",0,written.size())
        }
    }

    @Test fun anExtraCannotInjectTheProfileHeaderIntoAnUnmappedOperation(){
        for(mode in WriteMode.values())for((method,path) in listOf("POST" to "/feedback",
            "GET" to "/info?qualifier=displayCapabilities","POST" to "/info")){
            val written=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java){
                serializedRequest(mode,method,path,extra=mapOf("x-apple-protocolversion" to "1"),written=written)}
            assertEquals(0,written.size())
        }
    }

    @Test fun explicitRefusalKeepsStatusDistinctFromTransportFailure(){
        val refusal=assertThrows(RtspClient.StatusException::class.java){
            client(response("400 Bad Request","")).plist("SETUP","/")
        }
        assertEquals(400,refusal.status)
        assertThrows(EOFException::class.java){client(byteArrayOf()).plist("SETUP","/")}
    }

    /** 单向发送不读、不等：输入为空也能立刻返回。 */
    @Test fun oneWaySendReturnsWithoutWaitingForAResponse(){
        val out=ByteArrayOutputStream()
        val target=RtspClient(ByteArrayInputStream(byteArrayOf()),out)
        target.send("POST","/feedback")
        val written=out.toString("US-ASCII")
        assertTrue(written.startsWith("POST /feedback RTSP/1.0"))
        assertTrue("CSeq must be written","CSeq: 1\r\n" in written)
    }

    /** 单向发送的迟到响应必须按 CSeq 丢弃，不能被下一条请求当成自己的应答。 */
    @Test fun aLateResponseToAOneWaySendIsDiscarded(){
        val late="RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n"
        val real="RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n"
        val target=RtspClient(ByteArrayInputStream((late+real).toByteArray()),ByteArrayOutputStream())
        target.send("POST","/feedback")
        val response=target.request("GET","/info")
        assertEquals(2,response.headers["cseq"]?.toInt())
    }

    /** 异步模式：响应晚到也能完成请求；没人等的响应必须被读掉，不能堆在 socket 里。 */
    @Test fun theReaderCompletesRequestsAndDrainsUnmatchedResponses(){
        val pipeOut=java.io.PipedOutputStream()
        val pipeIn=java.io.PipedInputStream(pipeOut)
        val target=RtspClient(pipeIn,ByteArrayOutputStream())
        target.startReader()
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            target.send("POST","/feedback")
            val worker=pool.submit<RtspMessage>{target.request("GET","/info")}
            Thread.sleep(50)
            pipeOut.write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
            pipeOut.write("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n".toByteArray())
            pipeOut.flush()
            val reply=worker.get(3,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2,reply.headers["cseq"]?.toInt())
        }finally{target.close();pool.shutdownNow();pipeOut.close();pipeIn.close()}
    }

    @Test fun oneWayResponsesHaveTheirOwnHealthAndAreNotReportedAsUnmatched(){
        val pipeOut=java.io.PipedOutputStream()
        val pipeIn=java.io.PipedInputStream(pipeOut)
        val target=RtspClient(pipeIn,ByteArrayOutputStream())
        val received=java.util.concurrent.CountDownLatch(1)
        val traces=java.util.concurrent.CopyOnWriteArrayList<RtspClient.Exchange>()
        target.onExchange={traces+=it;if(it.phase==RtspClient.Phase.RESPONSE)received.countDown()}
        try{
            target.startReader()
            target.send("POST","/feedback")
            assertEquals(0,target.pendingCount)
            assertEquals(1,target.oneWayPendingCount)
            assertTrue(target.lastWriteNanos!=0L)
            assertEquals(0L,target.lastResponseNanos)
            pipeOut.write(response("200 OK"));pipeOut.flush()
            assertTrue(received.await(3,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0,target.oneWayPendingCount)
            val reply=traces.last()
            assertEquals("/feedback",reply.path)
            assertEquals("1",reply.responseCseq)
            assertNull(reply.error)
            assertTrue(target.lastResponseNanos!=0L)
            assertTrue(target.readerRunning)
        }finally{target.close();pipeOut.close();pipeIn.close()}
    }

    @Test fun idleReaderEofIsReportedAndLaterWritesFailWithoutWaiting(){
        val out=ByteArrayOutputStream()
        val target=RtspClient(ByteArrayInputStream(byteArrayOf()),out)
        val failed=java.util.concurrent.CountDownLatch(1)
        val traces=java.util.concurrent.CopyOnWriteArrayList<RtspClient.Exchange>()
        target.onExchange={traces+=it}
        target.onFailure={assertTrue(it is EOFException);failed.countDown()}
        try{
            target.startReader()
            assertTrue(failed.await(3,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(target.readerRunning)
            assertEquals(0,target.pendingCount)
            assertTrue(traces.any{it.phase==RtspClient.Phase.FAILED&&it.method=="reader"&&it.error=="EOFException"})
            assertThrows(IOException::class.java){target.send("POST","/feedback")}
            assertThrows(IOException::class.java){target.request("SETUP","/")}
            assertEquals(0,out.size())
        }finally{target.close()}
    }

    @Test fun readerEofDuringAWriteWakesTheRegisteredRequest(){
        val pipeOut=java.io.PipedOutputStream()
        val pipeIn=java.io.PipedInputStream(pipeOut)
        val writing=java.util.concurrent.CountDownLatch(1)
        val resumeWrite=java.util.concurrent.CountDownLatch(1)
        val failed=java.util.concurrent.CountDownLatch(1)
        val output=object:ByteArrayOutputStream(){
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                writing.countDown()
                check(resumeWrite.await(3,java.util.concurrent.TimeUnit.SECONDS))
                super.write(bytes,offset,length)
            }
        }
        val target=RtspClient(pipeIn,output)
        target.onFailure={failed.countDown()}
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            target.startReader()
            val worker=pool.submit<Throwable?>{try{target.request("TEARDOWN","/");null}catch(t:Throwable){t}}
            assertTrue(writing.await(3,java.util.concurrent.TimeUnit.SECONDS))
            pipeOut.close()
            assertTrue(failed.await(3,java.util.concurrent.TimeUnit.SECONDS))
            resumeWrite.countDown()
            assertTrue(worker.get(3,java.util.concurrent.TimeUnit.SECONDS) is EOFException)
            assertEquals(0,target.pendingCount)
        }finally{resumeWrite.countDown();target.close();pool.shutdownNow();pipeOut.close();pipeIn.close()}
    }

    @Test fun localCloseDoesNotReportAReaderFailure(){
        val pipeOut=java.io.PipedOutputStream()
        val pipeIn=java.io.PipedInputStream(pipeOut)
        val target=RtspClient(pipeIn,ByteArrayOutputStream())
        val failures=java.util.concurrent.atomic.AtomicInteger()
        target.onFailure={failures.incrementAndGet()}
        target.startReader()
        target.close();pipeOut.close();pipeIn.close()
        assertEquals(0,failures.get())
        assertThrows(IOException::class.java){target.send("POST","/feedback")}
    }

    private fun response(status:String,headers:String=""):ByteArray =
        ("RTSP/1.0 $status\r\nCSeq: 1\r\n"+headers+"Content-Length: 0\r\n\r\n").toByteArray(Charsets.US_ASCII)

    private fun client(input:ByteArray)=RtspClient(ByteArrayInputStream(input),ByteArrayOutputStream())

    @Test fun digestChallengeIsTerminalAndKeepsTheChallengeHeader(){
        val target=client(response("401 Unauthorized","WWW-Authenticate: Digest realm=\"AirPlay\"\r\n"))
        val failure=assertThrows(DigestRequired::class.java){target.request("POST","/pair-setup")}
        assertTrue(failure is IOException)
        assertTrue(failure.message!!,"Digest realm=\"AirPlay\"" in failure.message!!)
    }

    /** 404/501/405 这类答复不是传输失败：客户端拿到状态码后判定当前事务失败。 */
    @Test fun ordinaryErrorStatusesReachTheCaller(){
        listOf("404 Not Found","501 Not Implemented","405 Method Not Allowed").forEach{status->
            assertEquals(status,status.substringBefore(' ').toInt(),
                client(response(status)).request("POST","/pair-setup").status)
        }
    }

    @Test fun aResponseCannotChangeAnEstablishedSession(){
        val first=response("200 OK","Session: synthetic-first\r\n")
        val second=("RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: synthetic-second\r\nContent-Length: 0\r\n\r\n").toByteArray()
        val target=client(first+second)
        target.request("SETUP","/")
        assertThrows(IllegalArgumentException::class.java){target.request("RECORD","/")}
    }

    @Test fun malformedStatusAndHeaderNamesAreRejected(){
        listOf("RTSP/1.0 200extra OK\r\n\r\n","RTSP/1.0 200 OK\r\nBad Name: value\r\n\r\n").forEach{
            assertThrows(IllegalArgumentException::class.java){RtspWire.read(ByteArrayInputStream(it.toByteArray()))}
        }
    }

    @Test fun encryptedControlEofKeepsTheFailingRequestAndDoesNotLeakPayloads(){
        val traces=mutableListOf<RtspClient.Exchange>()
        val target=client(byteArrayOf())
        target.onExchange={traces+=it}
        target.encrypted(ByteArray(32){4})
        assertThrows(EOFException::class.java){target.request("POST","/feedback","private-payload".toByteArray())}
        assertEquals(listOf(RtspClient.Phase.SEND,RtspClient.Phase.FAILED),traces.map{it.phase})
        assertTrue(traces.all{it.method=="POST"&&it.path=="/feedback"&&it.cseq==1&&it.bodyBytes==15})
        assertEquals("EOFException",traces.last().error)
        assertTrue(traces.all{it.elapsedMs>=0})
        assertFalse(traces.toString().contains("private-payload"))
    }

    @Test fun responseTracingCannotChangeProtocolResults(){
        val traces=mutableListOf<RtspClient.Exchange>()
        val target=client(response("200 OK","Session: private-session\r\n"))
        target.onExchange={traces+=it;throw IllegalStateException("diagnostic sink failed")}
        assertEquals(200,target.request("SETUP","/").status)
        assertEquals(listOf(RtspClient.Phase.SEND,RtspClient.Phase.RESPONSE),traces.map{it.phase})
        assertEquals(200,traces.last().status)
        assertFalse(traces.toString().contains("private-session"))
    }
}
