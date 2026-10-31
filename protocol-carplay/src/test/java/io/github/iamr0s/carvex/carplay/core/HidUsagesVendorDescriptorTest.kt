package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用厂商实测描述符验证通用解析：车机的媒体键/通话键用的是**数组选择器**（报告字节 = 第几个 usage，
 *  0 = 释放），而且页面不一定是 0x0C/0x07 —— 第二串是 Telephony（0x0B）。
 *  这两串取自另一份可用实现里硬编码的两张按键表。 */
class HidUsagesVendorDescriptorTest {
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()

    private val consumerArray=hex("050C0901A10115002506050C0A00000AB0000AB1000AB5000AB6000AB9000ABC00750895018100C0")
    private val telephonyArray=hex("050B0907A10115002503050B0900092009210926750895018100C0")

    @Test fun pagesAreCollectedFromTheDescriptor(){
        assertEquals(setOf(0x0c),HidUsages.pages(consumerArray))
        assertEquals(setOf(0x0b),HidUsages.pages(telephonyArray))
    }

    @Test fun consumerArrayYieldsPlayPauseNextPrevious(){
        val usages=HidUsages.parse(consumerArray,HidUsages.PAGE_CONSUMER)
        assertEquals("selector 1 = play",setOf(0xb0),usages.decode(byteArrayOf(1)))
        assertEquals("selector 2 = pause",setOf(0xb1),usages.decode(byteArrayOf(2)))
        assertEquals("selector 3 = scan next",setOf(0xb5),usages.decode(byteArrayOf(3)))
        assertEquals("selector 4 = scan previous",setOf(0xb6),usages.decode(byteArrayOf(4)))
        assertEquals("selector 0 = released",emptySet<Int>(),usages.decode(byteArrayOf(0)))
    }

    @Test fun consumerPageCannotBeParsedOutOfATelephonyDescriptor(){
        // 只按 0x0C 解析 Telephony 描述符：没有任何该页字段，必须失败（而不是给出一个空设备）。
        assertEquals(null,runCatching{HidUsages.parse(telephonyArray,HidUsages.PAGE_CONSUMER)}.getOrNull())
    }

    @Test fun telephonyArrayParsesOnItsOwnPage(){
        val usages=HidUsages.parse(telephonyArray,0x0b)
        assertEquals("selector 1 = hook switch",setOf(0x20),usages.decode(byteArrayOf(1)))
        assertEquals("selector 3 = drop",setOf(0x26),usages.decode(byteArrayOf(3)))
        assertEquals("selector 0 = released",emptySet<Int>(),usages.decode(byteArrayOf(0)))
    }
}
