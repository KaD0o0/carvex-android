package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

class ControlRequestDeadlineTest {
    private class Incoming:InputStream() {
        private val bytes=LinkedBlockingQueue<Int>()
        override fun read():Int=bytes.take()
        fun reply(cseq:Int){
            "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                .toByteArray().forEach{bytes.put(it.toInt() and 255)}
        }
        override fun close(){bytes.offer(-1)}
    }

    @Test fun silentControlExpiresAndRejectsFurtherWrites(){
        val input=Incoming()
        val output=ByteArrayOutputStream()
        val client=RtspClient(input,output).apply{timeoutMs=80;startReader()}
        try{
            assertThrows(SocketTimeoutException::class.java){client.request("POST","/feedback")}
            assertFalse("A reply timeout must invalidate this control connection",client.readerRunning)
            val written=output.size()
            assertThrows(IOException::class.java){client.send("POST","/feedback")}
            assertThrows(IOException::class.java){client.request("TEARDOWN","/")}
            assertEquals(written,output.size())
        }finally{client.close();input.close()}
    }

    @Test fun anUnrelatedReplyCannotConfirmTheHeartbeat(){
        val input=Incoming()
        val output=object:ByteArrayOutputStream(){override fun flush(){input.reply(999)}}
        val client=RtspClient(input,output).apply{timeoutMs=80;startReader()}
        try{
            assertThrows(SocketTimeoutException::class.java){client.request("POST","/feedback")}
            assertFalse(client.readerRunning)
            assertEquals(0,client.pendingCount)
        }finally{client.close();input.close()}
    }
}
