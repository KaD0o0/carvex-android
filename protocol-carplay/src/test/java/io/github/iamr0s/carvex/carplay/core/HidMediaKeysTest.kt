package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HidMediaKeysTest {
    @Test fun consumerArrayReportUsesDeclaredUsageRange(){
        val descriptor=byteArrayOf(0x05,0x0c,0x09,0x01,0xa1.toByte(),0x01,0x19,0xb0.toByte(),0x29,0xb6.toByte(),0x15,0,0x25,0x06,0x75,0x08,0x95.toByte(),0x01,0x81.toByte(),0x00,0xc0.toByte())
        val keys=HidMediaKeys.parse(descriptor)
        assertEquals(setOf(0xb5),keys.decode(byteArrayOf(5)))
        assertEquals(emptySet<Int>(),keys.decode(byteArrayOf(7)))
    }
    @Test fun reportIdIsIncludedInLengthAndDecode(){
        val descriptor=byteArrayOf(0x05,0x0c,0x09,0x01,0xa1.toByte(),0x01,0x85.toByte(),0x02,0x19,0xb0.toByte(),0x29,0xb1.toByte(),0x15,0,0x25,1,0x75,0x08,0x95.toByte(),0x01,0x81.toByte(),0x00,0xc0.toByte())
        val keys=HidMediaKeys.parse(descriptor)
        assertEquals(setOf(0xb1),keys.decode(byteArrayOf(2,1)))
    }
    @Test fun profileSelectorsUseOrderedListNotBitMask(){
        val prefix=byteArrayOf(0x05,0x0c,0x09,1,0xa1.toByte(),1)
        val usages=byteArrayOf(0x09,0,0x09,0xb0.toByte(),0x09,0xb1.toByte(),0x09,0xb5.toByte(),0x09,0xb6.toByte(),0x09,0xb9.toByte(),0x09,0xbc.toByte())
        val suffix=byteArrayOf(0x15,0,0x25,6,0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
        val keys=HidMediaKeys.parse(prefix+usages+suffix)
        assertEquals(setOf(0xb5),keys.decode(byteArrayOf(3)))
        assertEquals(setOf(0xb6),keys.decode(byteArrayOf(4)))
        assertEquals(emptySet<Int>(),keys.decode(byteArrayOf(0)))
    }
}
