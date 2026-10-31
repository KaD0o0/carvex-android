package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** iap2-frame-spec §10：0x55 iAP lingo 消息包。期望字节均按 §10.2 的长度口径手工推算。 */
class IapLingoTest {
    private fun hex(s:String)=s.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun generalLingoCarriesSixteenBitCommand(){
        val bytes=IapLingo.build(4,0x004b,byteArrayOf(7,8))
        assertArrayEquals(hex("550504004B07089D"),bytes)
        val parsed=IapLingo.parse(bytes)
        assertEquals(4,parsed.lingo);assertEquals(0x4b,parsed.command)
        assertNull(parsed.transaction);assertArrayEquals(byteArrayOf(7,8),parsed.payload)
    }

    @Test fun otherLingosCarryEightBitCommand(){
        assertArrayEquals(hex("55020305F6"),IapLingo.build(3,0x05))
        assertArrayEquals(hex("550403070102EF"),IapLingo.build(3,0x07,transaction=0x0102))
        val parsed=IapLingo.parse(hex("550403070102EF"),hasTransaction=true)
        assertEquals(3,parsed.lingo);assertEquals(0x07,parsed.command);assertEquals(0x0102,parsed.transaction)
        assertTrue(parsed.payload.isEmpty())
    }

    @Test fun longFormatAboveShortLimit(){
        val payload=ByteArray(252){it.toByte()}
        val bytes=IapLingo.build(3,0x09,payload)
        assertEquals(259,bytes.size)
        assertEquals(0,bytes[1].toInt() and 255)
        assertEquals(254,IapWire.u16(bytes,2))
        assertArrayEquals(payload,IapLingo.parse(bytes).payload)
    }

    /** 校验覆盖 dst[1..total-2]：连首字节一起算就不成立，这正是它与链路帧校验的分界（§10.3 注）。 */
    @Test fun checksumExcludesLeadingTag(){
        val bytes=IapLingo.build(3,0x05)
        assertTrue(IapWire.valid(bytes,1,bytes.size-1))
        assertFalse(IapWire.valid(bytes,0,bytes.size))
    }

    @Test fun rejectsWrongLengthChecksumAndTag(){
        val bytes=IapLingo.build(4,0x0015,byteArrayOf(1,2,3))
        assertThrows(Exception::class.java){IapLingo.parse(bytes.copyOf(bytes.size-1))}
        val broken=bytes.copyOf();broken[broken.size-1]=(broken[broken.size-1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){IapLingo.parse(broken)}
        val wrongTag=bytes.copyOf();wrongTag[0]=0x54
        assertThrows(Exception::class.java){IapLingo.parse(wrongTag)}
        // 非 LINGO_GENERAL 的 command 只有 8 位，超出即拒绝
        assertThrows(Exception::class.java){IapLingo.build(3,0x1ff)}
        // transaction 缺失时按 8 位 command 解析，那两个字节落进 payload 而不是被吞掉
        assertEquals(0x07,IapLingo.parse(hex("550403070102EF")).command)
        assertEquals(2,IapLingo.parse(hex("550403070102EF")).payload.size)
    }
}
