package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** iap2-frame-spec §4：FID token 六型的字节布局。期望字节按 §4.2 的逐包还原手工推算。 */
class FidTokenTest {
    private fun hex(s:String)=s.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun accessoryCapsIsFixedElevenBytes(){
        val caps=hex("00 00 00 01 00 00 00 02")
        val bytes=FidToken.accessoryCaps(caps)
        assertArrayEquals(hex("0A 00 01")+caps,bytes)
        assertEquals(11,bytes.size)
        val token=FidToken.parse(bytes)
        assertEquals(FidToken.ACCESSORY_CAPS,token.type);assertArrayEquals(caps,token.content)
    }

    /** 0x02 的内容在 payload 前多一个保留字节，总长因此是 payload_len + 4。 */
    @Test fun accessoryInfoKeepsReservedByteBeforePayload(){
        val bytes=FidToken.accessoryInfo(hex("41 42"))
        assertArrayEquals(hex("05 00 02 00 41 42"),bytes)
        assertEquals(6,bytes.size)
        assertEquals(3,FidToken.parse(bytes).content.size)
    }

    @Test fun preferenceMetadataAndEaProtocol(){
        assertArrayEquals(hex("05 00 03 01 02 01"),FidToken.ipodPreference(1,2))
        assertArrayEquals(hex("04 00 08 03 04"),FidToken.eaProtocolMetadata(3,4))
        // 0x04：协议 ID 在内容首位、载荷跟在其后，总长 = payload_len + 4
        assertArrayEquals(hex("06 00 04 09 AA BB CC"),FidToken.eaProtocol(9,hex("AA BB CC")))
    }

    @Test fun bundleSeedIdIsFixedFourteenBytes(){
        val seed=hex("00 00 00 01 00 00 00 02 00 03 04")
        val bytes=FidToken.bundleSeedId(seed)
        assertEquals(14,bytes.size)
        assertArrayEquals(hex("0D 00 05")+seed,bytes)
    }

    @Test fun splitWalksConsecutiveTokens(){
        val stream=FidToken.accessoryCaps(hex("00 00 00 01 00 00 00 02"))+
                FidToken.ipodPreference(1,2)+FidToken.eaProtocolMetadata(3,4)
        val tokens=FidToken.split(stream)
        assertEquals(listOf(0x01,0x03,0x08),tokens.map{it.type})
        assertArrayEquals(stream,tokens.map{FidToken.build(it.type,it.content)}.reduce{a,b->a+b})
    }

    /** 文档 §9 未确认各类型的触发时机：别的固件版本可能发这里没有的类型，解析不能因此中断。 */
    @Test fun unknownTypesAreStillSliced(){
        val unknown=byteArrayOf(0x04,0x00,0x7f,0x11,0x22)
        val tokens=FidToken.split(unknown+FidToken.ipodPreference(1,2))
        assertEquals(listOf(0x7f,0x03),tokens.map{it.type})
        assertArrayEquals(byteArrayOf(0x11,0x22),tokens[0].content)
    }

    @Test fun rejectsInconsistentLengthsAndReservedByte(){
        val caps=FidToken.accessoryCaps(hex("00 00 00 01 00 00 00 02"))
        assertThrows(Exception::class.java){FidToken.parse(caps.copyOf(caps.size-1))}
        val reserved=caps.copyOf();reserved[1]=0x01
        assertThrows(Exception::class.java){FidToken.parse(reserved)}
        assertThrows(Exception::class.java){FidToken.split(caps.copyOf(3))}
        assertThrows(Exception::class.java){FidToken.accessoryCaps(hex("00 00 00 01"))}
        assertThrows(Exception::class.java){FidToken.bundleSeedId(hex("00"))}
        assertThrows(Exception::class.java){FidToken.ipodPreference(1,0x100)}
    }
}
