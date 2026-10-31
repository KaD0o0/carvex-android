package io.github.iamr0s.carvex.carplay.core

/** Consumer Control (0x0C) fields of a HID descriptor: the media keys the car sends. The decoding
 *  itself lives in [HidUsages], which the keyboard page (0x07) uses too. */
class HidMediaKeys private constructor(private val usages:HidUsages) {
    fun decode(report:ByteArray):Set<Int> = usages.decode(report)
    companion object {
        fun parse(desc:ByteArray):HidMediaKeys = HidMediaKeys(HidUsages.parse(desc,HidUsages.PAGE_CONSUMER))
    }
}
