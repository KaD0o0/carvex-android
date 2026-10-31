package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class HidMixedPageTest {
    private fun hex(value:String)=value.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    @Test fun arraySelectorsKeepPositionsOfOtherUsagePages(){
        // Selector 1 is Telephony hook, selector 2 is Consumer next. A page filter
        // must leave a placeholder rather than making selector 1 become NEXT.
        val descriptor=hex("05 0C 09 01 A1 01 15 00 25 02 09 00 0B 20 00 0B 00 0B B5 00 0C 00 75 08 95 01 81 00 C0")
        val consumer=HidUsages.parse(descriptor)
        val telephone=HidUsages.parse(descriptor,0x0b)
        assertEquals(setOf(0x0c,0x0b),HidUsages.pages(descriptor))
        assertEquals(emptySet<Int>(),consumer.decode(byteArrayOf(1)))
        assertEquals(setOf(0x20),telephone.decode(byteArrayOf(1)))
        assertEquals(setOf(0xb5),consumer.decode(byteArrayOf(2)))
        assertEquals(emptySet<Int>(),telephone.decode(byteArrayOf(2)))
    }

    @Test fun variableBitsKeepTheirDescriptorPositionsAndRejectOutOfRangeValues(){
        val descriptor=hex("05 0C 09 01 A1 01 15 00 25 01 0B 20 00 0B 00 09 B5 75 02 95 02 81 02 C0")
        val consumer=HidUsages.parse(descriptor)
        assertEquals(emptySet<Int>(),consumer.decode(byteArrayOf(1)))
        assertEquals(setOf(0xb5),consumer.decode(byteArrayOf(4)))
        assertEquals(emptySet<Int>(),consumer.decode(byteArrayOf(8)))
    }

    @Test fun malformedPageInventoryCannotReturnAPartiallyParsedDescriptor(){
        assertThrows(IllegalArgumentException::class.java){HidUsages.pages(hex("05 0C 0B B5"))}
        assertThrows(IllegalArgumentException::class.java){HidUsages.pages(hex("FE 04 01 00"))}
        // 上限跟固件一致：只受 iAP2 字段长度约束（65535），4 KB 不是上限。
        assertThrows(IllegalArgumentException::class.java){HidUsages.pages(ByteArray(65536))}
    }
}
