package io.github.iamr0s.carvex.carplay.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VideoTcp128Test {
    @Test fun keepAliveIsAHeaderOnlyRecordWithOpcodeTwo(){
        // 第 07 章 §2.6.1：128 字节明文头，0x04 = 2，无正文，0x7c 正文长度 f32 = 0。
        val frame=VideoWire.keepAlive()
        assertEquals(128,frame.size)
        val view=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0,view.getInt(0))
        assertEquals(2,frame[4].toInt())
        assertEquals(0,frame[5].toInt())
        assertEquals(0f,view.getFloat(124),0f)
    }
    @Test fun initialConfigUsesRecoveredDefaultGeometryAndZeroReservedFlags(){
        val payload=byteArrayOf(1,2,3)
        val packet=VideoWire.config(payload,1280,720)
        val header=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(131,packet.size)
        assertEquals(3,header.getInt(0))
        assertEquals(1,packet[4].toInt())
        // 第 07 章 §2.6：0x06 = AVC 基础 0x12 | 有 cryptor 的 0x04，0x07 = 1。
        assertEquals(0x16,packet[6].toInt() and 0xff)
        assertEquals(1,packet[7].toInt())
        assertEquals(0L,header.getLong(8))
        for(offset in listOf(16,40,56))assertEquals(1280f,header.getFloat(offset),0f)
        for(offset in listOf(20,44,60))assertEquals(720f,header.getFloat(offset),0f)
        for(offset in listOf(32,36,48,52))assertEquals(0f,header.getFloat(offset),0f)
        assertArrayEquals(payload,packet.copyOfRange(128,packet.size))
    }

    @Test fun framePreservesMonotonicNanosecondsAndAuthenticatesTheFinalHeader(){
        val key=ByteArray(32){it.toByte()}
        val payload=byteArrayOf(0,0,0,2,0x65,1)
        val monotonicNs=123_456_789_123L
        val packet=VideoWire.frame(payload,key,7,monotonicNs)
        val header=packet.copyOfRange(0,128)
        val view=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(payload.size+16,view.getInt(0))
        assertEquals(0,header[4].toInt())
        // 0x08 是 presentation NTP，0x10 才是原始单调时间戳；首个 NAL 是 0x65（IDR）→ 0x05 置 0x10。
        assertEquals(0L,view.getLong(8))
        assertEquals(monotonicNs,view.getLong(16))
        assertEquals(0x10,header[5].toInt() and 0xff)
        assertArrayEquals(payload,Crypto.open(key,Crypto.nonce(7),packet.copyOfRange(128,packet.size),header))
        header[8]=(header[8].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){Crypto.open(key,Crypto.nonce(7),packet.copyOfRange(128,packet.size),header)}
    }

    @Test fun invalidFramesFailBeforeAllocatingCiphertext(){
        assertThrows(IllegalArgumentException::class.java){VideoWire.frame(byteArrayOf(),ByteArray(32),0,0)}
        assertThrows(IllegalArgumentException::class.java){VideoWire.frame(byteArrayOf(1),ByteArray(16),0,0)}
        assertThrows(IllegalArgumentException::class.java){VideoWire.frame(byteArrayOf(1),ByteArray(32),-1,0)}
        assertThrows(IllegalArgumentException::class.java){VideoWire.frame(byteArrayOf(1),ByteArray(32),0,-1)}
    }
}
