package io.github.iamr0s.carvex.carplay.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Regression cases from the user's 2026-10-08 diagnostics; no session secrets. */
class VehicleRegressionTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun actualTouchUsageDescriptor(){
        val descriptor=hex("050d0904a101050d0922a102050d0933150025017501950181027507950181010501093015002680077510950181020931150026d002751095018102c0c0")
        val touch=HidTouch.parse(descriptor)
        val corner=touch.decode(hex("018007d002"),1920,720)
        assertTrue(corner.pressed);assertEquals(1919f,corner.x,0f);assertEquals(719f,corner.y,0f)
        val centre=touch.decode(hex("01c0036801"),1920,720)
        assertEquals(959.5f,centre.x,0.01f);assertEquals(359.5f,centre.y,0.01f)
        assertFalse(touch.decode(hex("0000000000"),1920,720).pressed)
        assertThrows(IllegalArgumentException::class.java){touch.decode(hex("010000"),1920,720)}
        val ambiguous=hex("050d0933094215002501750195028102050109300931150026ff7f751095028102")
        assertThrows(IllegalArgumentException::class.java){HidTouch.parse(ambiguous)}
        val multi=hex("050d0904a101050d0922a102050d0933150025017501950181027507950181030501093015002580751095018102093115002580751095018102c0050d0922a102050d0933150025017501950181027507950181030501093015002580751095018102093115002580751095018102c0c0")
        val multiTouch=HidTouch.parse(multi)
        assertTrue(multiTouch.decode(ByteArray(10){if(it==0)1 else 0},1600,900).pressed)
    }
    @Test fun actualTwoFingerCollectionsKeepPrimaryUntilRelease(){
        val descriptor=hex("050d0904a101050d0922a1020938750895018102150025010933750195018102950781030501260006093075109501810226c00309318102c0050d0922a1020938750895018102150025010933750195018102950781030501260006093075109501810226c00309318102c0c0")
        val parser=HidTouch.parse(descriptor)
        // Each contact is 6 bytes: descriptor-declared extra byte, flag, X16, Y16.
        val first=hex("0101000300"+"00"+"02010006c003")
        val down=parser.decode(first,1536,960)
        assertTrue(down.pressed)
        val stillSecond=parser.decode(hex("01000000000002010006c003"),1536,960)
        assertFalse(stillSecond.pressed)
        assertEquals(down.x,stillSecond.x,0f);assertEquals(down.y,stillSecond.y,0f)
        val next=parser.decode(hex("01000000000002010006c003"),1536,960)
        assertTrue(next.pressed);assertEquals(1535f,next.x,0f);assertEquals(959f,next.y,0f)
        val inside=parser.decode(hex("010000000000020100066003"),1536,960)
        assertTrue(inside.pressed);assertEquals(863.1f,inside.y,0.01f)
        assertThrows(IllegalArgumentException::class.java){parser.decode(ByteArray(11),1536,960)}
    }
    @Test fun dedicatedEventCanOmitCseqWithoutInventingIt(){
        val body=Plist.encode(mapOf("type" to "iAPSendMessage","params" to mapOf("data" to IapWire.detection)))
        val raw=ByteArrayOutputStream()
        RtspWire.write(raw,"POST /command RTSP/1.0",emptyMap(),body)
        val event=RtspWire.read(ByteArrayInputStream(raw.toByteArray()))
        assertFalse(event.headers.containsKey("cseq"))
        val replies=ByteArrayOutputStream()
        RtspClient.reply(replies,event,200,allowMissingCSeq=true)
        val reply=RtspWire.read(ByteArrayInputStream(replies.toByteArray()))
        assertEquals(200,reply.status);assertFalse(reply.headers.containsKey("cseq"));assertEquals(0,reply.body.size)
        assertThrows(IllegalStateException::class.java){RtspClient.reply(ByteArrayOutputStream(),event,200)}
        val numbered=event.copy(headers=event.headers+mapOf("cseq" to "17"))
        val numberedOut=ByteArrayOutputStream()
        RtspClient.reply(numberedOut,numbered,501,allowMissingCSeq=true)
        assertEquals("17",RtspWire.read(ByteArrayInputStream(numberedOut.toByteArray())).headers["cseq"])
    }
    @Test fun controlResponseDoesNotRequireAnEchoedSequence(){
        // 固件只写不读 CSeq，响应方也不回填；响应缺失或错号都不能算失败。
        for(headers in listOf(emptyMap(),mapOf("CSeq" to "9"))){
            val response=ByteArrayOutputStream()
            RtspWire.write(response,"RTSP/1.0 200 OK",headers)
            val client=RtspClient(ByteArrayInputStream(response.toByteArray()),ByteArrayOutputStream())
            assertEquals(200,client.request("POST","/feedback").status)
        }
    }
}
