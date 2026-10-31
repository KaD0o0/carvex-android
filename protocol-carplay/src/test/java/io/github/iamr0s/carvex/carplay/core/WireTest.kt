package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.*

class WireTest {
    @Test fun repeatedParametersPreserveOrderAndSingletonsRejectAmbiguity(){
        val data=IapWire.fields(listOf(12 to IapWire.cstring("zh"),6 to byteArrayOf(0x43,0x01),12 to IapWire.cstring("en")))
        val parsed=IapWire.parseFieldList(data)
        assertEquals(listOf(12,6,12),parsed.ordered.map{it.first})
        assertEquals(listOf("zh","en"),parsed.ordered.filter{it.first==12}.map{IapWire.text(it.second)})
        assertArrayEquals(byteArrayOf(0x43,0x01),parsed[6])
        assertThrows(IllegalArgumentException::class.java){parsed[12]}
        assertThrows(IllegalArgumentException::class.java){IapWire.parseFields(data)}
        assertThrows(IllegalArgumentException::class.java){IapWire.text("unterminated".toByteArray())}
    }
    private fun hex(s:String)=s.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun documentedFramesAndEveryReadBoundary(){
        val a=IapWire.Frame(0x40,1,0,10,IapWire.csm(0x1d00))
        val bytes=hex("FF5A00104001000A4C404000061D005D")
        assertArrayEquals(bytes,IapWire.packet(a))
        assertArrayEquals(hex("FF5A0009400001005D"),IapWire.packet(IapWire.Frame(0x40,0,1,0,byteArrayOf())))
        for(chunk in 1..bytes.size){
            val input=object:ByteArrayInputStream(bytes){
                override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(chunk,len))
            }
            val parsed=IapWire.read(input)
            assertEquals(a.seq,parsed.seq);assertEquals(a.session,parsed.session);assertArrayEquals(a.payload,parsed.payload)
        }
    }
    @Test fun packetVersion2UsesTenByteHeaderAndTwoByteChecksums(){
        val frame=IapWire.Frame(0x40,7,3,2,byteArrayOf(1,2,3,4,5))
        val wire=IapWire.packet(frame,packetVersion=2)
        assertEquals(10+5+2,wire.size)
        assertEquals(17,IapWire.u16(wire,2))
        val parsed=IapWire.read(ByteArrayInputStream(wire),packetVersion=2)
        assertEquals(frame.flags,parsed.flags);assertEquals(frame.seq,parsed.seq)
        assertEquals(frame.ack,parsed.ack);assertEquals(frame.session,parsed.session)
        assertArrayEquals(frame.payload,parsed.payload)
        assertThrows(Exception::class.java){IapWire.read(ByteArrayInputStream(wire))}
    }
    @Test fun rejectsCorruptionTruncationAndOversize(){
        val bytes=hex("FF5A00104001000A4C404000061D005D")
        for(i in bytes.indices){
            val bad=bytes.copyOf();bad[i]=(bad[i].toInt() xor 1).toByte()
            assertThrows(Exception::class.java){IapWire.read(ByteArrayInputStream(bad))}
        }
        for(n in 0 until bytes.size)assertThrows(Exception::class.java){IapWire.read(ByteArrayInputStream(bytes.copyOf(n)))}
        assertThrows(Exception::class.java){IapWire.read(ByteArrayInputStream(bytes),15)}
        assertThrows(Exception::class.java){IapWire.parseFields(hex("00030000"))}
    }
    @Test fun tlv8FragmentsAreNotIapTlv(){
        val data=ByteArray(600){it.toByte()}
        val bytes=Tlv8.encode(3 to data,Tlv8.state(2))
        assertArrayEquals(data,Tlv8.decode(bytes)[3])
        assertThrows(Exception::class.java){Tlv8.decode(hex("030101030102"))}
        assertThrows(Exception::class.java){Tlv8.decode(hex("0302ff"))}
    }
    @Test fun chachaRfc8439(){
        val key=hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce=hex("070000004041424344454647");val aad=hex("50515253c0c1c2c3c4c5c6c7")
        val plain="Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val expected=hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")
        assertArrayEquals(expected,Crypto.seal(key,nonce,plain,aad))
        val broken=expected.copyOf();broken[4]=(broken[4].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){Crypto.open(key,nonce,broken,aad)}
        assertThrows(Exception::class.java){Crypto.open(key,nonce,expected,aad+byteArrayOf(0))}
    }
    @Test fun pythonPlistFixture(){
        val b=javaClass.getResourceAsStream("/python.plist")!!.readBytes()
        val parsed=Plist.dictionary(b)
        assertEquals("车载",parsed["name"]);assertEquals(1280L,parsed["width"])
        assertArrayEquals(byteArrayOf(0,1,-1),parsed["hid"] as ByteArray)
        assertEquals(true,parsed["enabled"])
        val back=Plist.dictionary(Plist.encode(parsed));assertEquals(parsed["name"],back["name"])
        for(n in 0 until 40)assertThrows(Exception::class.java){Plist.decode(b.copyOf(n))}
        val malformed=b.copyOf();malformed[malformed.size-26]=0
        assertThrows(Exception::class.java){Plist.decode(malformed)}
    }
    @Test fun rtspExactLengthAndRecords(){
        val out=ByteArrayOutputStream()
        RtspWire.write(out,"RTSP/1.0 200 OK",mapOf("CSeq" to "1"),byteArrayOf(0,13,10,1))
        val result=RtspWire.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(200,result.status);assertArrayEquals(byteArrayOf(0,13,10,1),result.body)
        // 规格 §3.11 列出 HTTP/1.0、HTTP/1.1、RTSP/1.0 三种协议，响应行必须都能解析。
        assertEquals(200,RtspWire.read(ByteArrayInputStream("HTTP/1.0 200 OK\r\nCSeq: 1\r\n\r\n".toByteArray())).status)
        assertThrows(Exception::class.java){RtspWire.read(ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: -1\r\n\r\n".toByteArray()))}
        assertThrows(Exception::class.java){RtspWire.read(ByteArrayInputStream("RTSP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()))}
        val key=ByteArray(32){it.toByte()};val record=ByteArrayOutputStream();val data=ByteArray(3000){it.toByte()}
        RecordOutput(record,key).write(data)
        assertArrayEquals(data,IapWire.exact(RecordInput(ByteArrayInputStream(record.toByteArray()),key),data.size))
        val bad=record.toByteArray();bad[10]=(bad[10].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){RecordInput(ByteArrayInputStream(bad),key).read()}
    }
    @Test fun videoHeaderAndNalEncoding(){
        val config=VideoWire.config(hex("0164001fff"),1280,720)
        val b=ByteBuffer.wrap(config).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(5,b.getInt(0));assertEquals(1,config[4].toInt());assertEquals(1280f,b.getFloat(16),0f)
        assertEquals(0xe1b7dc2680000000UL.toLong(),VideoWire.ntp(1577934246500L))
        assertArrayEquals(hex("000000026501000000024102"),VideoWire.annexBToLengths(hex("0000000165010000014102")))
    }
    @Test(timeout=6000) fun linkRetransmitsSameSequenceAndDefersMessages(){
        val phoneIn=PipedInputStream(8192);val carOut=PipedOutputStream(phoneIn)
        val carIn=PipedInputStream(8192);val phoneOut=PipedOutputStream(carIn)
        val server=Executors.newSingleThreadExecutor()
        val future=server.submit {
            carOut.write(IapWire.detection);carOut.flush()
            assertArrayEquals(IapWire.detection,IapWire.exact(carIn,6))
            val syn=byteArrayOf(1,5)+IapWire.be16(2048)+IapWire.be16(100)+IapWire.be16(73)+byteArrayOf(1,3,10,0,1)
            carOut.write(IapWire.packet(IapWire.Frame(0x80,254,0,0,syn)));carOut.flush()
            val reply=IapWire.read(carIn);assertEquals(254,reply.ack)
            carOut.write(IapWire.packet(IapWire.Frame(0x40,254,reply.seq,0,byteArrayOf())));carOut.flush()
            carOut.write(IapWire.packet(IapWire.Frame(0x40,255,reply.seq,10,IapWire.csm(0x5703))));carOut.flush()
            IapWire.read(carIn)
            // Duplicate is ACKed and never delivered twice.
            carOut.write(IapWire.packet(IapWire.Frame(0x40,255,reply.seq,10,IapWire.csm(0x5703))));carOut.flush()
            IapWire.read(carIn)
            carOut.write(IapWire.packet(IapWire.Frame(0x40,0,reply.seq,10,IapWire.csm(0x4301))));carOut.flush()
            IapWire.read(carIn)
            val first=IapWire.read(carIn)
            val retry=IapWire.read(carIn)
            assertEquals(first.seq,retry.seq);assertArrayEquals(first.payload,retry.payload)
            carOut.write(IapWire.packet(IapWire.Frame(0x40,0,retry.seq,0,byteArrayOf())));carOut.flush()
            Thread.sleep(300)
        }
        val link=IapLink(phoneIn,phoneOut,{}, {})
        try{link.start();assertTrue(link.await(0x4301).isEmpty());assertTrue(link.await(0x5703).isEmpty());link.send(0x1d00);future.get(3,TimeUnit.SECONDS)}
        finally{link.close();server.shutdownNow();carOut.close();carIn.close()}
    }
    @Test fun hidDescriptorCoordinates(){
        val descriptor=hex("050d0942 15002501 75019501 8102 75079501 8103 0501 09300931 150026ff7f 75109502 8102")
        val touch=HidTouch.parse(descriptor)
        val sample=touch.decode(hex("01ff7fff7f"),1280,720)
        assertTrue(sample.pressed);assertEquals(1279f,sample.x,0f);assertEquals(719f,sample.y,0f)
        assertFalse(touch.decode(hex("0000000000"),1280,720).pressed)
        assertThrows(Exception::class.java){touch.decode(byteArrayOf(0),1280,720)}
        assertThrows(Exception::class.java){HidTouch.parse(hex("05010930150025ff750895018106"))}
    }
}
