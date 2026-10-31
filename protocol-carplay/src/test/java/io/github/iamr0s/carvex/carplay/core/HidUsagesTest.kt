package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** The keyboard page (0x07) carries the car's navigation keys and is read by the same field parser as
 *  Consumer Control, so a boot-keyboard descriptor has to decode to exactly the usages it declares. */
class HidUsagesTest {
    /** Standard boot keyboard: modifiers, one reserved byte, then six 8-bit usage slots. */
    private val bootKeyboard=byteArrayOf(
        0x05,0x01,0x09,0x06,0xa1.toByte(),0x01,
        0x05,0x07,0x19,0xe0.toByte(),0x29,0xe7.toByte(),0x15,0x00,0x25,0x01,0x75,0x01,0x95.toByte(),0x08,0x81.toByte(),0x02,
        0x95.toByte(),0x01,0x75,0x08,0x81.toByte(),0x01,
        0x95.toByte(),0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81.toByte(),0x00,
        0xc0.toByte()
    )
    private val consumerKeys=byteArrayOf(
        0x05,0x0c,0x09,0x01,0xa1.toByte(),0x01,0x19,0xb0.toByte(),0x29,0xb6.toByte(),
        0x15,0,0x25,0x06,0x75,0x08,0x95.toByte(),0x01,0x81.toByte(),0x00,0xc0.toByte()
    )

    @Test fun bootKeyboardDecodesTheUsagesItDeclares(){
        val keys=HidUsages.parse(bootKeyboard,HidUsages.PAGE_KEYBOARD)
        assertEquals(setOf(0x28),keys.decode(byteArrayOf(0,0,0x28,0,0,0,0,0)))
        assertEquals(setOf(0x29),keys.decode(byteArrayOf(0,0,0x29,0,0,0,0,0)))
    }

    @Test fun releasingAKeyReportsNothing(){
        val keys=HidUsages.parse(bootKeyboard,HidUsages.PAGE_KEYBOARD)
        assertEquals(setOf(0x4f),keys.decode(byteArrayOf(0,0,0x4f,0,0,0,0,0)))
        assertEquals(emptySet<Int>(),keys.decode(byteArrayOf(0,0,0,0,0,0,0,0)))
    }

    @Test fun modifierBitsAreReadAsTheirOwnUsages(){
        val keys=HidUsages.parse(bootKeyboard,HidUsages.PAGE_KEYBOARD)
        // Bit 0 of the modifier byte is usage 0xE0 (left control).
        assertEquals(setOf(0xe0),keys.decode(byteArrayOf(1,0,0,0,0,0,0,0)))
    }

    @Test fun theConsumerPageStillParsesTheSameWay(){
        assertEquals(setOf(0xb5),HidUsages.parse(consumerKeys).decode(byteArrayOf(5)))
        assertEquals(setOf(0xb5),HidMediaKeys.parse(consumerKeys).decode(byteArrayOf(5)))
    }

    @Test fun askingForAPageTheDescriptorDoesNotDescribeIsRefused(){
        val failure=assertThrows(IllegalArgumentException::class.java){
            HidUsages.parse(consumerKeys,HidUsages.PAGE_KEYBOARD)
        }
        assertTrue(failure.message!!,failure.message!!.contains("usage page 0x7"))
    }
}
